# Speak2UI: Voice-Driven Low-Level Mobile Interaction Using Context-Aware LLM Command Parsing

This study proposes Speak2UI, a voice-based mobile UI control system that leverages pre-trained large language models to reduce data dependency and enable direct voice control of low-level UI elements. The system comprises speech recognition with GPT-4o-mini-based command parsing supporting ten action types with context-aware filtering, and UI control modules.

## 🔎 Demo

![android demo](/assets/demo.png)

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
@article{Park23102026,
    author = {Sungjin Park and Jin A. Park and Jooyoung Lim and Hayoung Oh},
    title = {Speak2UI: Voice-Driven Low-Level Mobile Interaction Using Context-Aware LLM Command Parsing},
    journal = {International Journal of Human--Computer Interaction},
    pages = {1--14},
    year = {2026},
    publisher = {Taylor \& Francis},
    doi = {10.1080/10447318.2026.2745091},
    url = {https://doi.org/10.1080/10447318.2026.2745091}
}
```
