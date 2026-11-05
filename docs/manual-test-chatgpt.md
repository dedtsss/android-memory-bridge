# Manual verification with ChatGPT Android

This procedure must be run on an Android device with a debug or release Memory Bridge APK installed, a configured reachable Gateway (or the debug fake Gateway), and the official ChatGPT Android application. It is intentionally not asserted by automated tests: availability and replacement support are controlled by the source application and Android version.

1. In Memory Bridge configure a Gateway URL and token. For a debug-only offline-free check, use `mock://memory-gateway`.
2. In ChatGPT select several words in a response. Open the selection overflow menu (usually the three-dot menu).
3. Confirm **В память** is listed. Choose it, verify the exact whole selection is shown, enter a note, save, then verify the success screen and Undo if the Gateway returned an operation ID.
4. Select text in ChatGPT's editable message field. Open the same menu and confirm **Вспомнить** is listed. Choose it, wait for recall, and verify the selected query is replaced with:

   ```text
   Контекст из внешней памяти:

   <context-pack>

   Мой запрос:
   <original selection>
   ```

5. Select text in a read-only ChatGPT response and choose **Вспомнить**. Verify the Memory Bridge result page provides **Копировать**, **Скопировать и вернуться**, and **Закрыть**. Confirm no automatic insertion happened. The second action must copy the context-pack and finish Memory Bridge without launching ChatGPT or a browser.
6. Under a ChatGPT answer use Share. Choose Memory Bridge. Record whether ChatGPT sends full `text/plain` text or only a URL. If it sends only a URL, verify the explicit “full text was not received” notice and confirm the app did not download the URL.
7. Repeat with airplane mode and with a deliberately invalid token to confirm the original selected text remains unchanged on recall failure.

## Recorded result (2026-08-13)

The official ChatGPT Android test recorded the following:

- **В память** appeared in the `ACTION_PROCESS_TEXT` menu and accepted the selected text — PASS.
- **В память** saved through `mock://memory-gateway` — PASS.
- **Вспомнить** appeared in the `ACTION_PROCESS_TEXT` menu and received a context-pack — PASS.
- ChatGPT did not accept direct replacement of selected text in the composer; Memory Bridge showed the read-only fallback — source-app limitation.
- The old “copy and open ChatGPT” flow copied the text, but launching ChatGPT did not return to the current chat and could open a browser chooser. The fallback now uses **Скопировать и вернуться** and finishes the current Activity.
- ChatGPT Share sent a `chatgpt.com/s/...` URL instead of the full answer text. Memory Bridge records that URL as the Share payload and does not download it.

Device model, Android version, and ChatGPT version were not included in the source test report. Do not claim broader ChatGPT integration than these recorded observations establish.
