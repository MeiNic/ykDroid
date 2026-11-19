package net.pp3345.ykdroid.apdu.response.ykoath;

import net.pp3345.ykdroid.YubiKey;
import net.pp3345.ykdroid.apdu.ResponseApdu;

public class PutResponseApdu extends ResponseApdu {
	public PutResponseApdu(final byte[] response) {
		super(response);
	}

	/**
	 * Checks whether the response contains a result. A slot that is not configured for HMAC-SHA1
	 * challenge-response reports success without returning any data.
	 *
	 * @return true, if the response contains a complete challenge-response result.
	 */
	public boolean hasResult() {
		return this.response.length >= YubiKey.CHALLENGE_RESPONSE_LENGTH + 2;
	}

	public byte[] getResult() {
		final byte[] result = new byte[YubiKey.CHALLENGE_RESPONSE_LENGTH];

		System.arraycopy(this.response, 0, result, 0, YubiKey.CHALLENGE_RESPONSE_LENGTH);

		return result;
	}
}
