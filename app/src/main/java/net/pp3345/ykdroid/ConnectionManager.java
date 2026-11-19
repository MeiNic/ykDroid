package net.pp3345.ykdroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;

import net.pp3345.ykdroid.yubikey.NfcYubiKey;
import net.pp3345.ykdroid.yubikey.UsbYubiKey;

/**
 * Manages the lifecycle of a YubiKey connection via USB or NFC.
 */
class ConnectionManager extends BroadcastReceiver implements Application.ActivityLifecycleCallbacks {
	private final Activity activity;
	private       boolean  isActivityResumed;
	private       boolean  receiverRegistered;

	private static final String ACTION_USB_PERMISSION_REQUEST = "net.pp3345.ykdroid.intent.action.USB_PERMISSION_REQUEST";

	/**
	 * Flag used to indicate that support for USB host mode is present on the Android device.
	 */
	public static final byte CONNECTION_METHOD_USB = 0b1;
	/**
	 * Flag used to indicate that support for NFC is present on the Android device.
	 */
	public static final byte CONNECTION_METHOD_NFC = 0b10;

	private YubiKeyConnectReceiver   connectReceiver;
	private YubiKeyUsbUnplugReceiver unplugReceiver;

	/**
	 * Receiver interface that is called when a YubiKey was connected.
	 */
	interface YubiKeyConnectReceiver {
		/**
		 * Called when a YubiKey was connected via USB or NFC.
		 *
		 * @param yubiKey The YubiKey driver implementation, instantiated with a connection to the
		 *                YubiKey.
		 */
		void onYubiKeyConnected(YubiKey yubiKey);

		/**
		 * Called when the user denied access to a YubiKey connected via USB. The YubiKey needs to be
		 * attached again for the user to be asked for permission once more.
		 */
		void onUsbPermissionDenied();
	}

	/**
	 * Receiver interface that is called when a YubiKey connected via USB was unplugged.
	 */
	interface YubiKeyUsbUnplugReceiver {
		/**
		 * Called when a YubiKey connected via USB was unplugged.
		 */
		void onYubiKeyUnplugged();
	}

	/**
	 * May only be instantiated as soon as the basic initialization of a new activity is complete
	 * (usually in {@link Activity#onStart()}).
	 *
	 * @param activity As the connection lifecycle depends on the activity lifecycle, an active
	 *                 {@link Activity} must be passed
	 */
	ConnectionManager(final Activity activity) {
		this.activity = activity;
		this.activity.getApplication().registerActivityLifecycleCallbacks(this);
	}

	@Override
	public void onActivityCreated(final Activity activity, final Bundle savedInstanceState) {
	}

	/**
	 * Waits for a YubiKey to be connected. Should be called in {@link Activity#onStart()}.
	 *
	 * @param receiver The receiver implementation to be called as soon as a YubiKey was connected.
	 */
	public void waitForYubiKey(final YubiKeyConnectReceiver receiver) {
		this.connectReceiver = receiver;
	}

	@Override
	public void onActivityStarted(final Activity activity) {
		if (this.connectReceiver == null || (this.getSupportedConnectionMethods() & CONNECTION_METHOD_USB) == 0)
			return;

		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		this.registerReceiverCompat(new IntentFilter(ACTION_USB_PERMISSION_REQUEST));
		this.registerReceiverCompat(new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));

		assert usbManager != null;
		for (final UsbDevice device : usbManager.getDeviceList().values())
			this.requestPermission(device);
	}

	@Override
	public void onActivityResumed(final Activity activity) {
		if (this.connectReceiver == null || (this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) == 0)
			return;

		final IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);

		NfcAdapter.getDefaultAdapter(this.activity).enableForegroundDispatch(this.activity,
				PendingIntent.getActivity(this.activity, -1, new Intent(this.activity, this.activity.getClass()), Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_MUTABLE : 0),
		                                                                     new IntentFilter[]{filter},
		                                                                     new String[][]{new String[]{IsoDep.class.getName()}});
		this.isActivityResumed = true;
	}

	/**
	 * Waits until no YubiKey is connected.
	 *
	 * @param receiver The receiver implementation to be called as soon as no YubiKey is connected
	 *                 anymore.
	 */
	public void waitForYubiKeyUnplug(final YubiKeyUsbUnplugReceiver receiver) {
		if ((this.getSupportedConnectionMethods() & CONNECTION_METHOD_USB) == 0) {
			receiver.onYubiKeyUnplugged();
			return;
		}

		if (!this.isYubiKeyPlugged()) {
			receiver.onYubiKeyUnplugged();
			return;
		}

		this.unplugReceiver = receiver;
		this.registerReceiverCompat(new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
	}

	private boolean isYubiKeyPlugged() {
		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		assert usbManager != null;

		for (final UsbDevice device : usbManager.getDeviceList().values()) {
			if (UsbYubiKey.Type.isDeviceKnown(device))
				return true;
		}

		return false;
	}

	@Override
	public void onReceive(final Context context, final Intent intent) {
		assert intent.getAction() != null;

		switch (intent.getAction()) {
			case ACTION_USB_PERMISSION_REQUEST: {
				final UsbDevice device = getUsbDevice(intent);

				if (device == null || !this.isYubiKeyPlugged()) // Do not keep asking for permission to access a YubiKey that was unplugged already
					break;

				final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

				assert usbManager != null;
				if (usbManager.hasPermission(device)) {
					this.requestPermission(device); // Permission is present now, so this connects to the YubiKey
				} else if (this.connectReceiver != null) {
					// Do not ask again, as this would show the permission dialog in an endless loop
					this.connectReceiver.onUsbPermissionDenied();
				}
				break;
			}
			case UsbManager.ACTION_USB_DEVICE_ATTACHED:
				this.requestPermission(getUsbDevice(intent));
				break;
			case UsbManager.ACTION_USB_DEVICE_DETACHED: {
				final UsbDevice device = getUsbDevice(intent);

				if (device != null && this.unplugReceiver != null && UsbYubiKey.Type.isDeviceKnown(device)) {
					final YubiKeyUsbUnplugReceiver receiver = this.unplugReceiver;

					this.unregisterReceiverIfRegistered();
					this.unplugReceiver = null;
					receiver.onYubiKeyUnplugged();
				}
				break;
			}
			case NfcAdapter.ACTION_TECH_DISCOVERED:
				final IsoDep isoDep = IsoDep.get((Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));

				if (isoDep == null) {
					// Not a YubiKey
					return;
				}

				this.unregisterReceiverIfRegistered();

				this.connectReceiver.onYubiKeyConnected(new NfcYubiKey(isoDep));
				this.connectReceiver = null;
				break;
		}
	}

	@Override
	public void onActivityPaused(final Activity activity) {
		if (this.connectReceiver != null && (this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) != 0)
			NfcAdapter.getDefaultAdapter(this.activity).disableForegroundDispatch(this.activity);

		this.isActivityResumed = false;
	}

	@Override
	public void onActivityStopped(final Activity activity) {
		this.unregisterReceiverIfRegistered();
	}

	private void requestPermission(final UsbDevice device) {
		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		if (this.connectReceiver == null || device == null || !UsbYubiKey.Type.isDeviceKnown(device))
			return;

		assert usbManager != null;
		if (usbManager.hasPermission(device)) {
			this.unregisterReceiverIfRegistered();

			if ((this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) != 0 && this.isActivityResumed)
				NfcAdapter.getDefaultAdapter(this.activity).disableForegroundDispatch(this.activity);

			this.connectReceiver.onYubiKeyConnected(new UsbYubiKey(device, usbManager.openDevice(device)));
			this.connectReceiver = null;
		} else {
			// Since Android 14, mutable PendingIntents must be explicit. The intent has to stay mutable, as the system adds the device and the result to it.
			final Intent permissionIntent = new Intent(ACTION_USB_PERMISSION_REQUEST).setPackage(this.activity.getPackageName());

			usbManager.requestPermission(device, PendingIntent.getBroadcast(this.activity, 0, permissionIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
		}
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag") // The flag only exists (and is only required) on Android 13 and later
	private void registerReceiverCompat(final IntentFilter filter) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			// We only listen for broadcasts of the system and for the PendingIntent that we create ourselves
			this.activity.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			this.activity.registerReceiver(this, filter);
		}

		this.receiverRegistered = true;
	}

	/**
	 * Unregisters all registrations of this receiver, if there are any.
	 */
	private void unregisterReceiverIfRegistered() {
		if (this.receiverRegistered) {
			this.activity.unregisterReceiver(this);
			this.receiverRegistered = false;
		}
	}

	@SuppressWarnings("deprecation")
	private static UsbDevice getUsbDevice(final Intent intent) {
		// The typed variant is not used on older versions as it is broken on Android 13 (https://issuetracker.google.com/issues/240585930)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
			return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);

		return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
	}

	/**
	 * Gets the connection methods (USB and/or NFC) that are supported on the Android device.
	 *
	 * @return A byte that may or may not have the {@link #CONNECTION_METHOD_USB} and
	 * {@link #CONNECTION_METHOD_USB} bits set.
	 */
	public byte getSupportedConnectionMethods() {
		final PackageManager packageManager = this.activity.getPackageManager();
		byte                 result         = 0b0;

		if (packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
			result |= CONNECTION_METHOD_USB;

		if (packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) && NfcAdapter.getDefaultAdapter(this.activity).isEnabled())
			result |= CONNECTION_METHOD_NFC;

		return result;
	}

	@Override
	public void onActivitySaveInstanceState(final Activity activity, final Bundle outState) {
	}

	@Override
	public void onActivityDestroyed(final Activity activity) {
		this.activity.getApplication().unregisterActivityLifecycleCallbacks(this);
	}
}
