package net.pp3345.ykdroid.yubikey;

/**
 * Thrown when the YubiKey indicated failure status during a NFC operation.
 */
public class FailedOperationException extends YubiKeyException {
	public FailedOperationException() {
	}

	public FailedOperationException(final String message) {
		super(message);
	}
}
