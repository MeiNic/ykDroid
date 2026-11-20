package net.pp3345.ykdroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import net.pp3345.ykdroid.yubikey.ConnectionLostException;
import net.pp3345.ykdroid.yubikey.FailedOperationException;
import net.pp3345.ykdroid.yubikey.OtpNotAvailableException;
import net.pp3345.ykdroid.yubikey.Slot;
import net.pp3345.ykdroid.yubikey.SlotNotConfiguredException;
import net.pp3345.ykdroid.yubikey.TimeoutException;
import net.pp3345.ykdroid.yubikey.UsbYubiKey;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * May be invoked by Android apps using the
 * <code>"net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE"</code> intent to send a challenge
 * to a YubiKey and receive the response.
 * <p>
 * The challenge must be passed in an extra <code>byte[] challenge</code>. Upon successful completion,
 * the activity returns an extra <code>byte[] response</code> in the result intent. Optionally,
 * an extra <code>String purpose</code> may be passed in the intent to identify the purpose of the
 * challenge. ykDroid will use this identifier to remember and pre-select the slot used for each
 * purpose.
 * </p>
 */
public class ChallengeResponseActivity extends Activity implements ConnectionManager.YubiKeyConnectReceiver, ConnectionManager.YubiKeyUsbUnplugReceiver, AdapterView.OnItemSelectedListener {
	private static final String STATE_ERROR_MESSAGE = "errorMessage";
	private static final String STATE_SLOT_POSITION = "slotPosition";

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private ConnectionManager connectionManager;
	private SlotPreferenceManager slotPreferenceManager;
	private Slot selectedSlot;
	private String purpose;
	private byte[] challenge;
	private int promptMessage;
	/**
	 * Explains why the last attempt failed, to be shown when the activity is recreated. 0 if there
	 * was no failure.
	 */
	private int errorMessage;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		this.connectionManager = new ConnectionManager(this);
		this.slotPreferenceManager = new SlotPreferenceManager(this);

		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		this.setContentView(R.layout.activity_challenge_response);

		final TextView info = this.findViewById(R.id.info);

		this.challenge = this.getIntent().getByteArrayExtra("challenge");
		if (this.challenge == null || this.challenge.length == 0 || this.challenge.length > YubiKey.MAX_CHALLENGE_LENGTH) {
			this.showError();
			info.setText(R.string.invalid_challenge);
			return;
		}

		switch (this.connectionManager.getSupportedConnectionMethods()) {
			case ConnectionManager.CONNECTION_METHOD_USB | ConnectionManager.CONNECTION_METHOD_NFC:
				this.promptMessage = R.string.attach_or_swipe_yubikey;
				break;
			case ConnectionManager.CONNECTION_METHOD_USB:
				this.promptMessage = R.string.attach_yubikey;
				break;
			case ConnectionManager.CONNECTION_METHOD_NFC:
				this.promptMessage = R.string.swipe_yubikey;
				break;
			default:
				info.setText(R.string.no_supported_connection_method);
				this.showError();
				return;
		}

		this.purpose = this.getIntent().getStringExtra("purpose");
		this.selectedSlot = this.slotPreferenceManager.getPreferredSlot(this.purpose, Slot.CHALLENGE_HMAC_1);

		info.setText(this.promptMessage);

		if (savedInstanceState != null) {
			// The activity was recreated after a failed attempt: Explain what went wrong and keep the slot that was tried so that it can be changed
			this.errorMessage = savedInstanceState.getInt(STATE_ERROR_MESSAGE, 0);
			this.selectedSlot = slotAt(savedInstanceState.getInt(STATE_SLOT_POSITION, positionOf(this.selectedSlot)));

			if (this.errorMessage != 0) {
				info.setText(this.getErrorText(this.selectedSlot, this.promptMessage));
				this.setWarningIcon(true);
			}
		}

		final Spinner slotSelection = this.findViewById(R.id.slotSelection);

		slotSelection.setSelection(positionOf(this.selectedSlot));
		slotSelection.setOnItemSelectedListener(this);

		this.connectionManager.waitForYubiKey(this);
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		super.onSaveInstanceState(outState);

		outState.putInt(STATE_ERROR_MESSAGE, this.errorMessage);

		if (this.selectedSlot != null)
			outState.putInt(STATE_SLOT_POSITION, positionOf(this.selectedSlot));
	}

	@Override
	protected void onDestroy() {
		// A running operation is left alone, as it will eventually time out. We just do not accept new ones.
		this.executor.shutdown();

		super.onDestroy();
	}

	@Override
	public void onYubiKeyConnected(final YubiKey yubiKey) {
		// This also replaces the explanation of a previous failure, if there was one
		((TextView) this.findViewById(R.id.info)).setText(yubiKey instanceof UsbYubiKey ? R.string.press_button : this.promptMessage);
		this.findViewById(R.id.slotSelection).setVisibility(View.GONE);
		this.setWarningIcon(false);
		this.errorMessage = 0;

		final Slot   slot      = this.selectedSlot;
		final byte[] challenge = this.challenge;

		this.executor.execute(() -> {
			byte[]    response = null;
			Exception failure  = null;

			try {
				response = yubiKey.challengeResponse(slot, challenge);
			} catch (final Exception e) {
				failure = e;
			} finally {
				yubiKey.close();
			}

			final byte[]    finalResponse = response;
			final Exception finalFailure  = failure;

			this.runOnUiThread(() -> this.onChallengeResponseFinished(slot, finalResponse, finalFailure));
		});
	}

	private void onChallengeResponseFinished(final Slot slot, final byte[] response, final Exception failure) {
		if (this.isFinishing() || this.isDestroyed())
			return;

		if (failure == null) {
			this.slotPreferenceManager.setPreferredSlot(this.purpose, slot);

			final Intent result = new Intent();
			result.putExtra("response", response);
			this.setResult(RESULT_OK, result);
			this.finish();
		} else {
			Log.e("ykDroid", "Error during challenge-response request", failure);

			// With USB, the YubiKey has to be unplugged first. Then (or immediately, with NFC) the activity is recreated and offers another attempt.
			this.errorMessage = getErrorMessage(failure);
			this.connectionManager.waitForYubiKeyUnplug(this);
			this.showError();

			((TextView) this.findViewById(R.id.info)).setText(this.getErrorText(slot, R.string.unplug_yubikey));
		}
	}

	private static int getErrorMessage(final Exception failure) {
		if (failure instanceof SlotNotConfiguredException || failure instanceof TimeoutException)
			return R.string.slot_not_responding;

		if (failure instanceof OtpNotAvailableException)
			return R.string.otp_not_available;

		if (failure instanceof ConnectionLostException)
			return R.string.connection_lost;

		if (failure instanceof FailedOperationException)
			return R.string.request_rejected;

		return R.string.error_try_again;
	}

	@Override
	public void onUsbPermissionDenied() {
		((TextView) this.findViewById(R.id.info)).setText(R.string.usb_permission_denied);
		this.setWarningIcon(true);
	}

	@Override
	public void onYubiKeyUnplugged() {
		this.recreate();
	}

	@Override
	public void onItemSelected(final AdapterView<?> parent, final View view, final int position, final long id) {
		this.selectedSlot = slotAt(position);
	}

	@Override
	public void onNothingSelected(final AdapterView<?> parent) {
		// The previous selection stays in place
	}

	private static Slot slotAt(final int position) {
		switch (position) {
			case 0:
				return Slot.CHALLENGE_HMAC_1;
			case 1:
				return Slot.CHALLENGE_HMAC_2;
			default:
				throw new IllegalStateException();
		}
	}

	private static int positionOf(final Slot slot) {
		return slot == Slot.CHALLENGE_HMAC_2 ? 1 : 0;
	}

	private void showError() {
		this.setWarningIcon(true);
		this.findViewById(R.id.slotSelection).setVisibility(View.GONE);
	}

	/**
	 * Shows a warning sign instead of the progress indicator.
	 */
	private void setWarningIcon(final boolean warning) {
		this.findViewById(R.id.waiting).setVisibility(warning ? View.GONE : View.VISIBLE);
		this.findViewById(R.id.failure).setVisibility(warning ? View.VISIBLE : View.GONE);
	}

	/**
	 * Explains why the last attempt with the given slot failed, followed by a hint on how to continue.
	 */
	private String getErrorText(final Slot slot, final int hint) {
		final String[] slots    = this.getResources().getStringArray(R.array.slots);
		final int      position = positionOf(slot);

		// Messages may refer to the slot that was tried (first argument) and to the other one (second argument). The slot number is kept on the same line as the word "Slot".
		final String tried = slots[position].replace(' ', '\u00A0');
		final String other = slots[1 - position].replace(' ', '\u00A0');

		return this.getString(this.errorMessage, tried, other) + "\n\n" + this.getString(hint);
	}
}
