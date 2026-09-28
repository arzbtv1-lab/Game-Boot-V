name: Build APK
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: unzip -q ./*.zip
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.7"
          build-root-directory: IgnisDeck
      - run: gradle assembleDebug --no-daemon
        working-directory: IgnisDeck
      - uses: actions/upload-artifact@v4
        with:
          name: IgnisDeck-apk
          path: IgnisDeck/app/build/outputs/apk/debug/app-debug.apk
