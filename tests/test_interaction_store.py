"""Host persistence regression using SDK JSON sources and temporary file adapters.

Exercises the real two store classes; does not claim to test Android AtomicFile,
Controller/process lifecycle or a device. Requires installed SDK sources and JDK.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

HARNESS = r'''
package com.ds.phoneagent;
import java.io.*;
import java.nio.file.*;
import org.json.*;
public class StoreProbe {
    static final String SESSION="e87c0ac8-ff83-44c4-b6c5-12ece16c7490";
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static JSONObject pending() throws Exception {
        return new JSONObject().put("id","question-1").put("kind","question").put("api_key","must-not-persist")
            .put("input",new JSONObject().put("questions",new JSONArray().put(new JSONObject()
                .put("question","选择哪一种？").put("header","选择").put("multiSelect",false)
                .put("options",new JSONArray().put(new JSONObject().put("label","甲").put("description","第一个"))
                    .put(new JSONObject().put("label","乙").put("description","第二个"))))));
    }
    static JSONObject record(String state) throws Exception {
        return new JSONObject().put("state",state).put("prompt","test").put("status","test status")
            .put("output","existing output").put("process","existing process").put("result","").put("terminal","")
            .put("session_id",SESSION).put("pending_question",pending()).put("interaction_answer","选甲")
            .put("started_at",100).put("updated_at",200).put("ended_at",0);
    }
    static JSONObject lastTurn(JSONObject conversation) throws Exception {
        JSONArray turns=conversation.getJSONArray("turns"); return turns.getJSONObject(turns.length()-1);
    }
    static void questionIntact(JSONObject value) throws Exception {
        check(value.getJSONObject("pending_question").getJSONObject("input").getJSONArray("questions")
            .getJSONObject(0).getString("question").equals("选择哪一种？"),"Question changed");
        check(value.getString("interaction_answer").equals("选甲"),"Answer lost");
        check(!value.toString().contains("must-not-persist"),"Unknown sensitive field retained");
    }
    static JSONObject assistant(String id,String tool) throws Exception {
        JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text","private output"));
        if(tool!=null)content.put(new JSONObject().put("type","tool_use").put("id",tool).put("name","Read")
            .put("input",new JSONObject().put("secret","must-not-persist")));
        return new JSONObject().put("type","assistant").put("session_id",SESSION)
            .put("uuid","event-"+id).put("message",new JSONObject().put("id",id).put("content",content));
    }
    static JSONObject result(String query,int turns) throws Exception {
        return new JSONObject().put("type","result").put("session_id",SESSION).put("uuid","result-"+query)
            .put("user_message_uuid",query).put("num_turns",turns);
    }
    static void metricsProbe(File root) throws Exception {
        ExecutionMetrics metrics=new ExecutionMetrics();
        metrics.beginQuery("q1");
        JSONObject first=assistant("m1","tool1");
        check(metrics.observe(first),"Initial assistant ignored");
        check(!metrics.observe(first),"Assistant replay counted twice");
        check(!metrics.observe(assistant("m1",null).put("uuid","another-block")),"Same API reply split into multiple messages counted twice");
        metrics.observe(assistant("m2",null)); metrics.observe(result("q1",2));
        metrics.beginQuery("q2");
        for(int i=0;i<6;i++)metrics.observe(assistant("file-model-"+i,i<5?"file-tool-"+i:null));
        metrics.observe(result("q2",6));
        metrics.beginQuery("interrupted");
        metrics.observe(result("interrupted",2).put("is_error",true).put("subtype","error_during_execution"));
        JSONObject retry=new JSONObject().put("type","system").put("subtype","api_retry").put("uuid","retry1")
            .put("session_id",SESSION).put("attempt",1).put("error_status",500);
        metrics.observe(retry); metrics.recordPhoneAction("phone-action-1");
        JSONObject paused=record("paused"); metrics.writeTo(paused);
        check(!paused.getJSONObject("execution_metrics").toString().contains("must-not-persist"),"Tool arguments leaked into metrics");
        LastTaskStore last=new LastTaskStore(new File(root,"metrics-last")); last.write(paused);
        metrics=ExecutionMetrics.fromRecord(new LastTaskStore(new File(root,"metrics-last")).recoverInterrupted());
        check(!metrics.observe(first)&&!metrics.observe(retry)&&!metrics.recordPhoneAction("phone-action-1"),"Persisted ledger replay counted twice");
        check(!metrics.observe(result("q2",6)),"Old result replay recounted after resume");
        metrics.beginQuery("q4"); metrics.observe(assistant("m9",null)); metrics.observe(result("q4",1));
        check(metrics.engineTurns()==11,"Per-query engine rounds not accumulated including interrupted result");
        check(metrics.modelReplyCount()==9&&metrics.toolCallCount()==6,"Model/tool semantics incorrect");
        check(metrics.apiRetryCount()==1&&metrics.phoneActionCount()==1,"Retry/phone counters incorrect");
        check(!metrics.isIncomplete()&&!metrics.hasUnsettledQuery(),"Settled task unexpectedly incomplete");
        metrics.observe(new JSONObject(retry.toString()).put("uuid","retry2"));
        check(metrics.apiRetryCount()==2,"Repeated attempt=1 on distinct request chain was deduplicated");
        TaskHistoryStore history=new TaskHistoryStore(new File(root,"metrics-history"));history.initialize(null);
        JSONObject started=history.beginTurn("","metrics");String cid=started.getString("conversation_id"),tid=started.getString("turn_id");
        metrics.writeTo(paused);history.updateTurn(cid,tid,paused);
        history.updateTurn(cid,tid,record("paused")); // Older progress caller must not drop the ledger.
        history=new TaskHistoryStore(new File(root,"metrics-history"));history.initialize(null);
        ExecutionMetrics restored=ExecutionMetrics.fromRecord(lastTurn(history.getConversation(cid)));
        check(restored.engineTurns()==11&&restored.apiRetryCount()==2&&restored.modelReplyCount()==9,"History normalized away metrics");
        check(!restored.observe(result("q4",1))&&!restored.observe(retry),"History recovery lost deduplication");
        ExecutionMetrics unsettled=new ExecutionMetrics();unsettled.beginQuery("unfinished");
        unsettled.observe(assistant("unfinished-reply","unfinished-tool"));unsettled.observe(retry);
        JSONObject running=record("running");unsettled.writeTo(running);
        LastTaskStore crashed=new LastTaskStore(new File(root,"metrics-crashed-last"));crashed.write(running);
        JSONObject recovered=crashed.recoverInterrupted();String recoveredOutput=recovered.getString("output");
        check(recoveredOutput.contains(unsettled.summary())&&recoveredOutput.contains("统计不完整"),"Last-task crash footer lost unsettled metrics");
        check(!recoveredOutput.contains("\n执行轮数：0"),"Last-task recovery mislabeled an unsettled zero as complete");
        ExecutionMetrics recoveredMetrics=ExecutionMetrics.fromRecord(recovered);
        check(recoveredMetrics.hasUnsettledQuery()&&recoveredMetrics.modelReplyCount()==1&&!recoveredMetrics.observe(retry),"Crash recovery changed replay ledger");
        String recoveredOnce=recovered.toString();
        check(new LastTaskStore(new File(root,"metrics-crashed-last")).recoverInterrupted().toString().equals(recoveredOnce),"Modern crash recovery duplicated footer");
        TaskHistoryStore crashedHistory=new TaskHistoryStore(new File(root,"metrics-crashed-history"));crashedHistory.initialize(null);
        JSONObject crashStart=crashedHistory.beginTurn("","unfinished");String crashCid=crashStart.getString("conversation_id"),crashTid=crashStart.getString("turn_id");
        crashedHistory.updateTurn(crashCid,crashTid,running);
        crashedHistory=new TaskHistoryStore(new File(root,"metrics-crashed-history"));crashedHistory.initialize(null);
        JSONObject recoveredTurn=lastTurn(crashedHistory.getConversation(crashCid));String recoveredTerminal=recoveredTurn.getString("terminal");
        check(recoveredTerminal.contains(unsettled.summary())&&recoveredTerminal.contains("统计不完整"),"History crash footer lost unsettled metrics");
        check(!recoveredTerminal.contains("\n执行轮数：0")&&ExecutionMetrics.fromRecord(recoveredTurn).hasUnsettledQuery(),"History crash recovery presented a complete zero");
        LastTaskStore legacyCrash=new LastTaskStore(new File(root,"legacy-crash"));legacyCrash.write(record("running").put("num_turns",2));
        String legacyOutput=legacyCrash.recoverInterrupted().getString("output");
        check(legacyOutput.contains("\n执行轮数：2")&&!legacyOutput.contains("累计执行轮次"),"Legacy recovery presentation changed");
        check(ExecutionMetrics.fromRecord(new JSONObject().put("num_turns",2)).isIncomplete(),"Legacy final-query value called complete");
        ExecutionMetrics bounded=new ExecutionMetrics();
        for(int i=0;i<ExecutionMetrics.MAX_IDENTITIES+100;i++)bounded.recordPhoneAction("bounded-"+i);
        JSONObject saved=new JSONObject();bounded.writeTo(saved);
        check(saved.toString().length()<100000,"Replay ledger exceeded serialized bound");
        bounded=ExecutionMetrics.fromRecord(saved);
        check(bounded.isIncomplete()&&bounded.phoneActionCount()==ExecutionMetrics.MAX_IDENTITIES,"Ledger limit must retain a lower bound");
        check(!bounded.recordPhoneAction("bounded-0")&&!bounded.recordPhoneAction("never-seen"),"Bounded replay ledger evicted IDs or grew after restore");
        ExecutionMetrics uncorrelated=new ExecutionMetrics();uncorrelated.beginQuery("unknown-query");
        JSONObject oldResult=result("old",3);oldResult.remove("user_message_uuid");
        uncorrelated.observe(oldResult);uncorrelated.observe(oldResult);
        check(uncorrelated.engineTurns()==3&&uncorrelated.isIncomplete(),"Uncorrelated result replay inflated rounds");
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);
        metricsProbe(root);
        for(String state:new String[]{"paused","waiting_user","pausing","resuming"}) {
            File directory=new File(root,"last-"+state);
            LastTaskStore store=new LastTaskStore(directory); store.write(record(state));
            JSONObject restored=new LastTaskStore(directory).recoverInterrupted();
            check(restored.getString("state").equals(state.equals("pausing")||state.equals("resuming")?"paused":state),"Last-state recovery");
            check(restored.getString("session_id").equals(SESSION),"Last session lost");
            check(!restored.getString("output").contains("【任务结束】"),"Pause became terminal");
            questionIntact(restored);
            check(!store.acknowledge(),"Suspended task acknowledged");
            String once=restored.toString();
            check(new LastTaskStore(directory).recoverInterrupted().toString().equals(once),"Last recovery not idempotent");
        }
        LastTaskStore active=new LastTaskStore(new File(root,"running")); active.write(record("running"));
        JSONObject interrupted=active.recoverInterrupted();
        check(interrupted.getString("state").equals("interrupted"),"Running no longer interrupted");
        check(interrupted.getString("output").contains("【任务结束】"),"Missing running terminal log");
        for(String state:new String[]{"paused","waiting_user","pausing","resuming"}) {
            File directory=new File(root,"history-"+state);
            TaskHistoryStore history=new TaskHistoryStore(directory); history.initialize(null);
            JSONObject started=history.beginTurn("","test");
            String id=started.getString("conversation_id"), turn=started.getString("turn_id");
            history.bindSession(id,SESSION); history.updateTurn(id,turn,record(state));
            JSONObject progress=record(state); progress.remove("pending_question"); progress.remove("interaction_answer");
            history.updateTurn(id,turn,progress);
            TaskHistoryStore rebuilt=new TaskHistoryStore(directory); rebuilt.initialize(null);
            JSONObject conversation=rebuilt.getConversation(id), result=lastTurn(conversation);
            String expected=state.equals("pausing")||state.equals("resuming")?"paused":state;
            check(result.getString("state").equals(expected)&&conversation.getString("status").equals(expected),"History state recovery");
            check(conversation.getString("session_id").equals(SESSION),"History session lost");
            check(result.getString("terminal").isEmpty(),"Suspension has terminal footer"); questionIntact(result);
            String once=conversation.toString(); rebuilt=new TaskHistoryStore(directory); rebuilt.initialize(null);
            check(rebuilt.getConversation(id).toString().equals(once),"History recovery not idempotent");
            JSONObject clear=record("running").put("pending_question",JSONObject.NULL).put("interaction_answer",JSONObject.NULL);
            rebuilt.updateTurn(id,turn,clear);
            check(!lastTurn(rebuilt.getConversation(id)).has("pending_question")&&!lastTurn(rebuilt.getConversation(id)).has("interaction_answer"),"Explicit clear ignored");
        }
        LastTaskStore bounded=new LastTaskStore(new File(root,"bounds")); bounded.write(record("waiting_user"));
        String before=bounded.read().toString();
        StringBuilder huge=new StringBuilder(); for(int i=0;i<=LastTaskStore.INTERACTION_ANSWER_LIMIT;i++)huge.append('x');
        try { bounded.write(record("waiting_user").put("interaction_answer",huge.toString())); throw new AssertionError("Oversize answer accepted"); }
        catch(IOException expected) { }
        check(bounded.read().toString().equals(before),"Rejected answer changed stored data");
        JSONObject invalid=record("waiting_user"); invalid.getJSONObject("pending_question").put("kind","execute");
        try { bounded.write(invalid); throw new AssertionError("Invalid interaction kind accepted"); } catch(IOException expected) { }
        check(bounded.read().toString().equals(before),"Rejected question changed stored data");
        System.out.println("PASS store interaction recovery, exact questions, bounds, explicit clears and sessions");
    }
}
'''


class InteractionStoreTests(unittest.TestCase):
    def test_real_store_state_and_interaction_round_trips(self):
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
        candidates = list((sdk / "sources").glob("android-*/org/json/JSONObject.java"))
        jdk = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
        if not candidates or not (jdk / "bin/javac").exists():
            self.skipTest("Store host regression requires installed Android SDK sources and JDK")
        source = max(candidates, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parents[2].name)))).parent
        with tempfile.TemporaryDirectory(prefix="phoneagent-store-test-") as directory:
            temp = Path(directory)
            stubs = {
                "android/content/Context.java": "package android.content; public abstract class Context { public abstract java.io.File getFilesDir(); }",
                "android/compat/annotation/UnsupportedAppUsage.java": "package android.compat.annotation; public @interface UnsupportedAppUsage {}",
                "android/annotation/SystemApi.java": "package android.annotation; public @interface SystemApi { Client client(); public enum Client { MODULE_LIBRARIES } }",
                "libcore/util/NonNull.java": "package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface NonNull {}",
                "libcore/util/Nullable.java": "package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}",
                "android/util/AtomicFile.java": '''package android.util; import java.io.*; import java.nio.file.*;
                    public class AtomicFile { private final File file; public AtomicFile(File f){file=f;}
                    public File getBaseFile(){return file;} public FileInputStream openRead()throws IOException{return new FileInputStream(file);}
                    public FileOutputStream startWrite()throws IOException{file.getParentFile().mkdirs(); return new FileOutputStream(file+".new");}
                    public void finishWrite(FileOutputStream out)throws IOException{out.close(); Files.move(new File(file+".new").toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
                    public void failWrite(FileOutputStream out){try{out.close();}catch(IOException e){} new File(file+".new").delete();}
                    public void delete(){file.delete();new File(file+".new").delete();new File(file+".bak").delete();}}
                    ''',
                "com/ds/phoneagent/LinuxRuntime.java": 'package com.ds.phoneagent; public class LinuxRuntime { public static boolean isSessionId(String s){return s!=null && s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}}',
                "com/ds/phoneagent/StoreProbe.java": HARNESS,
            }
            for name, value in stubs.items():
                path = temp / "src" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value)
            json_target = temp / "src/org/json"
            json_target.mkdir(parents=True)
            for name in ("JSON", "JSONArray", "JSONException", "JSONObject", "JSONStringer", "JSONTokener"):
                shutil.copyfile(source / (name + ".java"), json_target / (name + ".java"))
            classes = temp / "classes"
            classes.mkdir()
            real = ROOT / "app/src/main/java/com/ds/phoneagent"
            compilation = subprocess.run([str(jdk / "bin/javac"), "-encoding", "UTF-8", "-d", str(classes),
                *map(str, (temp / "src").rglob("*.java")), str(real / "LastTaskStore.java"), str(real / "TaskHistoryStore.java"),
                str(real / "ExecutionMetrics.java")],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(compilation.returncode, 0, compilation.stderr)
            result = subprocess.run([str(jdk / "bin/java"), "-cp", str(classes), "com.ds.phoneagent.StoreProbe", str(temp / "data")],
                capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS store interaction", result.stdout)


if __name__ == "__main__":
    unittest.main()
