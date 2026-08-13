1. **Update Settings UI**: Modify `activity_settings.xml` to include options for "Cloudflare AI" checkbox, "Worker URL" text field, "API Key" text field, and "Default Prompt" text field.
2. **Handle Settings in Activity**: Update `SettingsActivity.kt` to read and save these preferences using `SharedPreferences`. Show or hide the detailed settings (URL, Key, Prompt) based on the checkbox state.
3. **Extend InpaintManager Engine**: Add `CLOUDFLARE` to the `Engine` enum in `InpaintManager.kt`. Create a `getEngine` method.
4. **Create CloudflareProcessor**: Create `CloudflareProcessor.kt` to handle multipart/form-data upload to the Cloudflare Worker URL, pass the required parameters (image, mask, prompt), and return the decoded bitmap result.
5. **Update EditorActivity Options**: In `EditorActivity.kt`, conditionally add "Cloudflare (AI)" to the Inpaint Engine dropdown if the setting is enabled.
6. **Implement Inpaint Dialogs**: In `EditorActivity.kt`, when triggering Inpainting and `Cloudflare` is selected:
    - First, show a prompt dialog (`dialog_cloudflare_prompt.xml`) for the user to edit the prompt, saving any changes to SharedPreferences.
    - Second, show a progress dialog (`dialog_inpaint_loading.xml`) while `InpaintManager.inpaint` is running.
7. **Integrate InpaintManager**: In `InpaintManager.kt`, call `CloudflareProcessor.inpaint` when the engine is `CLOUDFLARE`. Pass the stored URL, API key, and prompt. If it fails, fall back to OpenCV. Add `CloudflareProcessor.ProgressListener` interface and callback handling to update the UI progress bar.
8. **Create Worker Script**: Create a basic `workers.js` script tailored for `@cf/runwayml/stable-diffusion-v1-5-inpainting` as requested by the user, for deploying to Cloudflare.
9. **Finalize**: Run `gradle -x lint assembleDebug` to make sure it builds successfully.
