# PhoneAgent 0.4.0 design QA

## Visual target and scope

The user selected the third displayed visual direction, then requested concentrated controls at the top and conversation bubbles underneath. This is an update to the existing native Android app, not a web prototype.

- Selected concept: `/Users/ds/.codex/generated_images/01a1a4b2-9cd4-7c81-ae4c-a1c0472cb9df/exec-1cfddef2-f306-42cc-a405-adc3367051aa.png`.
- Refined visual truth shown before implementation: `/Users/ds/.codex/generated_images/01a1a4b2-9cd4-7c81-ae4c-a1c0472cb9df/exec-c576150b-4e3d-49b9-970d-b78c8ab7d415.png`.
- Source is 853 × 1844 pixels, designed for a 390 × 844 app viewport.
- Device captures are 1080 × 2388 pixels on the Huawei CTR-AL00. Android system bars are outside the comparison scope. The source and native screen are compared at equivalent content width; no raster was stretched or used as the interface.
- State: a completed conversation, process collapsed, input empty, keyboard closed. Actual persisted task text and table columns are longer than the illustrative mock; they scroll rather than being rewritten to match sample data.

## Comparison history

### Initial capture

The source image and `local/v040-detail.png` were opened together in the same comparison input. Focused follow-up views used `local/v040-table.png`, `local/v040-history.png` and the keyboard captures.

- [P2, resolved] Small secondary text used `#877263` on `#FBF6EE` (contrast 4.23:1). Changed it to `#796353` (5.24:1), consistent with the Markdown renderer.
- [P2, resolved] The history screen rendered both navigation actions with the primary fill. Only history now receives that fill in history mode; new-task mode restores the left primary action.
- No clipped persistent controls, wrong mascot asset, nested result cards or missing conversation actions were observed in the initial portrait capture.

### Final capture

The refined source, `local/v040-detail-final.png` and `local/v040-history-final.png` were opened together in one comparison input after final installation. The darker metadata is readable and the history page has one highlighted navigation action. No actionable P0/P1/P2 finding remains.

The full-view images are legible enough to inspect the brand, original cat, text wrapping, bubble edges, process control and composer without extra crops. The separate `local/v040-table.png` capture verifies table detail and horizontal scrolling. The extra header status line is retained intentionally for actual runtime feedback; longer real answers scroll within the conversation. Native system bars and the different idle pet frame are expected differences, not substituted design assets.

## Required fidelity surfaces

- Typography: native Chinese sans-serif, 28sp brand, 17sp request, 15sp Markdown body and 12–13sp metadata. Hierarchy and wrapping checked using real content; full result text remains selectable. Header status is limited to two lines, while complete task reasons remain in conversation data.
- Spacing/layout: fixed compact controls above a scrolling conversation, right request bubbles, left response bubbles and fixed composer. Native rounded bubbles replace the mock's decorative tails. Process and Markdown share one response surface, with no extra result card.
- Colors: ivory `#FBF6EE`, cocoa `#463329`, terracotta `#AE5C43`, peach user bubble `#F3DDCF`, cream response `#FFFCF7`. Primary white-on-terracotta text has 4.76:1 contrast. Small secondary text receives the contrast fix above.
- Images: the original cat icon is used for reply avatars; the existing original sprite animation remains in the header. Its current idle pose differs from the illustrative mock deliberately, preserving the user's state-dependent pet behavior. No screenshot backgrounds or replacement mascot drawings are used.
- Copy/content: app copy remains Chinese and reflects actual task states. Connection says “已就绪” only when local runtime, control and configuration are ready; it does not claim an API connectivity test. No fake success messages or sample concert data were inserted into user history.

## Interaction evidence

`local/v040-ui-smoke.json` records passing non-destructive checks for history navigation, swipe reveal/close, opening existing conversations, process expansion/collapse, horizontal Markdown tables, per-conversation drafts, portrait keyboard layout, settings keyboard mode and a landscape short window. No task was submitted, model called or real record deleted. Rotation preferences were restored. History, last-task and encrypted preferences hashes remained unchanged.

Editing connection settings keeps stop/reset visible and disables task submission. Short windows hide the brand area so navigation and actions remain accessible. No PhoneAgent crash was found in the inspected recent crash buffer; accessibility remained actually bound.

## Final verification and follow-up

- Final package: 0.4.0 / code 9, SHA256 `e62324a6887a962853b82c20a5a06c6ca47994b62e7473a8a36e60433457f9e3`.
- Final detail and history captures inspected; both reported visual issues resolved.
- `local/v040-final-verification.json` confirms unchanged data hashes and an actually bound accessibility service after the final replacement installation.
- Scope limits: default device font size, portrait and one landscape keyboard state were checked. No new external-model task was executed for a visual-only change. This pass does not claim exhaustive device/font-scale coverage.
- Optional P3: future refinement may further reduce the header's generic idle-status text. It is currently readable and does not hide persistent controls.

final result: passed
