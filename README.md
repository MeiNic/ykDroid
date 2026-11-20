# ykDroid

ykDroid is an Android app that provides an interface for integrating the challenge-response functionality
of YubiKeys into other apps. Both USB and NFC (any YubiKey with NFC, e.g. the YubiKey NEO or the YubiKey 5 NFC) are
supported on compatible devices.

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=net.pp3345.ykdroid)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/net.pp3345.ykdroid/)

## Integration
ykDroid provides an [Intent](https://developer.android.com/reference/android/content/Intent.html) called
`net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE`, which accepts an extra `byte[] challenge` and returns an extra
`byte[] response`. Optionally, an extra `String purpose` may be passed additionally in the intent to identify the purpose
of the challenge. ykDroid will use this identifier to remember and pre-select the slot used for each purpose.

Upon invocation, ykDroid will automatically detect which connection methods (USB and/or NFC) are available and show a dialog
overlay with instructions to the user.

Example code:
```java
private static final int CHALLENGE_RESPONSE_REQUEST_CODE = 12345;

private void startChallengeResponse(final byte[] challenge) {
    final Intent intent = new Intent("net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE");
    intent.putExtra("challenge", challenge);
    intent.putExtra("purpose", "some-unique-purpose-identifier"); // optional

    startActivityForResult(intent, CHALLENGE_RESPONSE_REQUEST_CODE);
}

public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
    if(requestCode == CHALLENGE_RESPONSE_REQUEST_CODE && resultCode == RESULT_OK) {
        final byte[] response = data.getByteArrayExtra("response");
        // ...
    }
}
```

## Using your YubiKey
* ykDroid uses the HMAC-SHA1 challenge-response feature of your YubiKey. It must be configured for it in one of the two
  slots (e.g. using YubiKey Manager or the YubiKey Personalization Tool). Select that slot in the dialog that ykDroid
  shows. Your choice is remembered.
* Over USB, the OTP interface of your YubiKey must be enabled.
* Over NFC, OTP must be enabled for NFC, and the slot must not be configured to require touching the button. Hold the
  YubiKey against the NFC antenna of your device until the dialog closes.

## Apps that integrate ykDroid
* [Keepass2Android](https://play.google.com/store/apps/details?id=keepass2android.keepass2android) - Password manager
  app compatible with KeePass
* [ykpass](https://github.com/noliran/ykpass) - Password app that derives passwords directly from the YubiKey's response value

## Building
You need a JDK (17 or newer) and the Android SDK with platform 36 (`ANDROID_HOME` must point to it):
```
./gradlew assembleDebug
```
The APK is created in `app/build/outputs/apk/debug/`. Release builds (`./gradlew assembleRelease`) are unsigned and have
to be aligned and signed using `zipalign` and `apksigner` before they can be installed. Android only accepts updates
signed with the same key, so an already installed ykDroid from another source must be uninstalled first.

## Contributing
PRs welcome! I am open for adding more YubiKey functionality, but bugfixes and additional translations are also very
appreciated :-)

## Bugs & issues
Please use the GitHub issue tracker for reporting bugs and feature requests.

## Notice
Yubico and YubiKey are registered trademarks of Yubico. Google Play and the Google Play logo are trademarks of Google LLC.
