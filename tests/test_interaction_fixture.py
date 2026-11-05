"""Offline regressions for the deterministic native-interaction fixture; no device or model."""
import unittest
from interaction_fixture import CLAUDE_MD_MARKER, Fixture, MEMORY_MARKER, QUESTION


def request(text, results=(), tools=()):
    messages = [{"role": "user", "content": text}]
    if results:
        messages.append({"role": "user", "content": list(results)})
    return {"messages": messages, "tools": [{"name": name} for name in tools]}


class InteractionFixtureTests(unittest.TestCase):
    def test_recovered_answer_requires_original_native_question_and_current_user_answer(self):
        fixture = Fixture()
        original = fixture.response(request("PROBE_ASK", tools=["AskUserQuestion"]))["content"]
        body = {"messages": [{"role": "user", "content": "PROBE_ASK"},
                             {"role": "assistant", "content": original},
                             {"role": "user", "content": QUESTION + ": Blue PROBE_RECOVERED_ANSWER"}]}
        self.assertEqual(fixture.response(body)["content"][0]["text"], "PROBE_RECOVERED_ANSWER_OK")
        with self.assertRaises(AssertionError):
            fixture.response({"messages": body["messages"][1:]})
        with self.assertRaises(AssertionError):
            fixture.response({"messages": [body["messages"][0], body["messages"][2]]})

    def test_status_exposes_only_synchronization_flags(self):
        fixture = Fixture()
        fixture.response(request("PROBE_AFTER_INTERRUPT"))
        self.assertEqual(fixture.status(), {"slow_started": False, "request_count": 1})

    def test_merged_interrupted_user_message_uses_the_latest_instruction(self):
        fixture = Fixture()
        response = fixture.response(request("PROBE_INTERRUPT\n[Request interrupted by user]\nPROBE_AFTER_INTERRUPT"))
        self.assertEqual(response["content"][0]["text"], "PROBE_AFTER_INTERRUPT_OK")
        self.assertFalse(fixture.slow_started.is_set())

    def test_native_question_requires_the_actual_answer_tool_result(self):
        fixture = Fixture()
        response = fixture.response(request("PROBE_ASK", tools=["AskUserQuestion"]))
        self.assertEqual(response["content"][0]["input"]["questions"][0]["question"], QUESTION)
        result = {"type": "tool_result", "tool_use_id": "probe_ask", "content": "Blue"}
        self.assertEqual(fixture.response(request("PROBE_ASK", [result]))["content"][0]["text"], "PROBE_ASK_OK")
        with self.assertRaises(AssertionError):
            fixture.response(request("PROBE_ASK", [{**result, "is_error": True}]))

    def test_native_memory_can_arrive_in_user_system_reminders(self):
        fixture = Fixture()
        fixture.response(request("PROBE_AFTER_INTERRUPT\n<system-reminder>" + MEMORY_MARKER + CLAUDE_MD_MARKER + "</system-reminder>"))
        self.assertTrue(fixture.requests[0]["native_memory_loaded"])
        self.assertTrue(fixture.requests[0]["native_claude_md_loaded"])

    def test_file_errors_cannot_be_misreported_as_success(self):
        with self.assertRaises(AssertionError):
            Fixture().response(request("PROBE_FILES", [{"type": "tool_result", "tool_use_id": "probe_file_0", "is_error": True}],
                                       tools=["Write", "Read", "Edit", "Glob", "Grep"]))


if __name__ == "__main__":
    unittest.main()
