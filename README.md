# Speak2UI: Voice-Driven Assistive Interfaces for Precision Mobile Accessibility

This study proposes "Speak2UI", a novel voice-based mobile UI control system that leverages pre-trained large language models to minimize data dependency and enable direct voice control of low-level UI elements. The system comprises Voice Activity Detection-based speech recognition, GPT-4o-mini command parsing supporting ten action types with context-aware filtering, and UI control modules. Performance evaluation compared command recognition accuracy with the existing system.

## 🔎 Demo

<video src="https://private-user-images.githubusercontent.com/75429815/529877528-e2450ec9-2145-4c65-860d-99f40e621ca9.mp4?jwt=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3NzU4MDUyNTQsIm5iZiI6MTc3NTgwNDk1NCwicGF0aCI6Ii83NTQyOTgxNS81Mjk4Nzc1MjgtZTI0NTBlYzktMjE0NS00YzY1LTg2MGQtOTlmNDBlNjIxY2E5Lm1wND9YLUFtei1BbGdvcml0aG09QVdTNC1ITUFDLVNIQTI1NiZYLUFtei1DcmVkZW50aWFsPUFLSUFWQ09EWUxTQTUzUFFLNFpBJTJGMjAyNjA0MTAlMkZ1cy1lYXN0LTElMkZzMyUyRmF3czRfcmVxdWVzdCZYLUFtei1EYXRlPTIwMjYwNDEwVDA3MDkxNFomWC1BbXotRXhwaXJlcz0zMDAmWC1BbXotU2lnbmF0dXJlPTg3OWM4NjBjYzFkNzMxOTM4Zjc2OWY2YzYzNGI4NTA5YjFlZjExMDc5MDE3NWUwNGQ0YTJlY2ExNjRhNDY1YWYmWC1BbXotU2lnbmVkSGVhZGVycz1ob3N0In0.QNQADJyCknkNi6xxK3PiovGi7Da0x2Z13btHTurnn1g"></video>

Test Environment: Samsung S8+ (Android API level 34)

## 📑 Build Environment

This section outlines the environment settings required to build the project successfully.

- Compile Sdk Version: 36
- Min Sdk Version: 30
- Java Version: 21
- Kotlin Compiler Extension Version: 1.5.0

## ⚙️ Installation & Build

1. **Clone the project**

    ```sh
    git clone https://github.com/Jin-A-Park/speak2ui.git
    ```

2. **Set up the API Key**
    This project requires an OpenAI API key to function.

    - Create a file named `local.properties` in the **root directory** of the project.
    - Add your OpenAI API key to the file as shown below:

    ```properties
    OPENAI_API_KEY=YOUR_OPENAI_API_KEY_HERE
    ```

3. **Open the project in Android Studio**

    - Launch Android Studio and select `Open` to open the cloned project folder.
    - Wait for the Gradle sync to complete.

4. **Run the app**

    - Select your desired emulator or physical device.
    - Click the 'Run' button to build and run the application.

## 📱 Activate

1. Enable `Speak2UI` in Settings > Accessibility.
2. Turn on `Display over other apps` in Settings > App info > Speak2UI.
3. Tap the MIC button in the top-left corner.
4. Tap `On`, and you’re all set!

## 📝 Citataion

```bib
@article{ART003300241,
  author  = {Sung-Jin Park and Jin-A Park and Ha-Young Oh},
  title   = {Speak2UI: Voice Assistance Technology for Mobile Accessibility},
  journal = {Journal of the Korea Institute of Information and Communication Engineering},
  year    = {2026},
  volume  = {30},
  number  = {1},
  pages   = {46--53},
  issn    = {2234-4772}
}
```
