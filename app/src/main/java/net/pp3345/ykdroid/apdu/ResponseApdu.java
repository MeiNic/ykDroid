package net.pp3345.ykdroid.apdu;

/**
 * ISO 7816-4 5.3.3
 */
public class ResponseApdu {
	protected final byte[] response;

	public ResponseApdu(final byte[] response) {
		this.response = response;
	}

	/**
	 * Gets the status word (SW1 and SW2) at the end of the response.
	 *
	 * @return The status word or -1 if the response is too short to contain one.
	 */
	public int getStatusWord() {
		if (this.response.length < 2)
			return -1;

		return ((this.response[this.response.length - 2] & 0xff) << 8) | (this.response[this.response.length - 1] & 0xff);
	}

	public boolean isSuccess() {
		return this.getStatusWord() == 0x9000;
	}
}
