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

5. Select text in a read-only ChatGPT response and choose **Вспомнить**. Verify the Memory Bridge result page provides **Копировать**, **Копировать и открыть ChatGPT**, and **Закрыть**. Confirm no automatic insertion happened.
6. Under a ChatGPT answer use Share. Choose Memory Bridge. Record whether ChatGPT sends full `text/plain` text or only a URL. If it sends only a URL, verify the explicit “full text was not received” notice and confirm the app did not download the URL.
7. Repeat with airplane mode and with a deliberately invalid token to confirm the original selected text remains unchanged on recall failure.

Record device model, Android version, ChatGPT version, and the observed Share payload. Do not claim ChatGPT integration is verified until these observations are recorded.
