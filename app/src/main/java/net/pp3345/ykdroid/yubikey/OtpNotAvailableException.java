package net.pp3345.ykdroid.yubikey;

/**
 * Thrown when a YubiKey connected via USB does not provide the OTP interface that is required for
 * challenge-response, for example because it was disabled using YubiKey Manager.
 */
public class OtpNotAvailableException extends YubiKeyException {
}
