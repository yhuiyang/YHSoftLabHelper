package com.yhsoftlab.helper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.gms.appstate.AppStateManager;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.GamesActivityResultCodes;
import com.google.android.gms.plus.Plus;

public class GoogleApiHelper extends Fragment implements
		GoogleApiClient.ConnectionCallbacks,
		GoogleApiClient.OnConnectionFailedListener {

	// ===========================================================
	// Constants
	// ===========================================================
	private final String LOG_TAG = "GoogleApiHelper";

	/* argument keys */
	public final static String KEY_ARG_REQCODE_RESOLVE_SIGNIN = "ResolveSignIn";
	public final static String KEY_ARG_REQCODE_SHOW_LEADERBOARD = "ShowLeaderboard";
	public final static String KEY_ARG_LISTENER_IMPL = "ListenerImpl";
	public final static String KEY_ARG_AUTO_SIGNIN = "AutoSignIn";

	/* argument values */
	public final static int VAL_ARG_yy = 1;

	/* request codes */
	private final int REQCODE_RESOLVE_SIGNIN_DEFAULT = 10001;
	private final int REQCODE_SHOW_LEADBOARD = 10002;

	// ===========================================================
	// Fields
	// ===========================================================
	private GoogleApiClient mApiClient = null;
	private IListener mListener = null;
	/* Allow activity overwrite this value to avoid conflict. */
	private int mRCResolveSignIn = REQCODE_RESOLVE_SIGNIN_DEFAULT;
	private int mRCShowLeaderboard = REQCODE_SHOW_LEADBOARD;
	/**
	 * Initially, this value is setup by activity via
	 * {@link android.support.v4.app.Fragment#setArguments(Bundle)
	 * setArguments(Bundle)}. After user initiate sign in flow completed and
	 * succeed, this will be updated to true. After user requests to sign out,
	 * this will be updated to false too.
	 */
	private boolean mAutoSignIn = false;
	private boolean mDuringUserInitiateSignInFlow = false;
	/**
	 * Set when start GoogleApiClient.connect(), and clear when connected or
	 * connection fail is resolved or not resolved.
	 */
	private boolean mConnecting = false;
	/**
	 * true during we're waiting result from activity that provide resolution.
	 * otherwise false.
	 */
	private boolean mWaitingActivityResult = false;

	// ===========================================================
	// Constructors
	// ===========================================================

	// ===========================================================
	// Methods for/from SuperClass/Interfaces
	// ===========================================================
	@Override
	public void onConnectionFailed(ConnectionResult result) {
		Log.i(LOG_TAG, "onConnectionFailed[" + result.hasResolution()
				+ "]. Detail: " + result.toString());

		boolean tryToResolve = false;

		if (mDuringUserInitiateSignInFlow) {
			Log.v(LOG_TAG, "Helper will try to resolve this failure.");
			tryToResolve = resolveConnectionResult(result);
		} else {
			Log.v(LOG_TAG, "Helper wont resolve failure by auto sign-in.");
		}

		if (!tryToResolve) {
			setHelperDisconnected();
			notifyHelperListener(Boolean.FALSE);
		}
	}

	@Override
	public void onConnected(Bundle connectionHint) {
		Log.i(LOG_TAG, "onConnected: " + connectionHint);

		setHelperConnected();
		notifyHelperListener(Boolean.TRUE);
	}

	@Override
	public void onConnectionSuspended(int cause) {
		Log.i(LOG_TAG, "onConnectionSuspended: " + cause);
	}

	/*
	 * we're return from an activity that was launched to resolve connection
	 * problem.
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		LogTraceEnter("onActivityResult: " + requestCode + "," + resultCode);

		if (requestCode != mRCResolveSignIn) {
			Log.w(LOG_TAG, "Request code is not interested, skip...");
			return;
		}

		if (!mConnecting) {
			Log.w(LOG_TAG,
					"Helper is not connecting, check why we receive result?");
			return;
		}

		mWaitingActivityResult = false; /* mark result is arrived */
		mConnecting = false; /* connection completed */

		if (mApiClient != null) {

			switch (resultCode) {
			case Activity.RESULT_OK:
				Log.v(LOG_TAG, "Resolved, re-connect again...");
				helperConnectToService(mDuringUserInitiateSignInFlow);
				break;
			case Activity.RESULT_CANCELED:
				Log.v(LOG_TAG, "Resolution canceled.");
				notifyHelperListener(null);
				break;
			case GamesActivityResultCodes.RESULT_RECONNECT_REQUIRED:
				Log.v(LOG_TAG, "Reconnect required");
				mApiClient.reconnect();
				break;
			case GamesActivityResultCodes.RESULT_APP_MISCONFIGURED:
				Log.e(LOG_TAG, " ****** App Misconfigured ******");
				Log.e(LOG_TAG, " ****** Package name: "
						+ getActivity().getPackageName());
				Log.e(LOG_TAG, " ****** Cert SHA1 fingerprint: "
						+ getSHA1CertFingerprint(getActivity()));
				Log.e(LOG_TAG, " ****** App Id: "
						+ getAppIdFromResource(getActivity()));
				break;
			default:
				// TODO: make description clear */
				Toast.makeText(
						getActivity(),
						"Connection problem[" + resultCode
								+ "] can not be resolve", Toast.LENGTH_LONG)
						.show();
				notifyHelperListener(false);
				break;
			}
		} else {
			Log.w(LOG_TAG, "mApiClient is null, check why?");
		}

		LogTraceLeave("onActivityResult");
	}

	@Override
	public void onAttach(Activity activity) {
		LogTraceEnter("onAttach: " + activity);
		super.onAttach(activity);

		/**
		 * Setup listener:<br>
		 * 1. Check if activity pass a CListener object in arguments bundle as
		 * listener.<br>
		 * 2. If not, use activity itself as the listener.<br>
		 * 3. If not, this is error<br>
		 */
		Bundle args = this.getArguments();
		if (args != null && args.containsKey(KEY_ARG_LISTENER_IMPL)) {
			mListener = args.getParcelable(KEY_ARG_LISTENER_IMPL);
		}
		if (mListener != null && mListener instanceof CListener) {
			Log.i(LOG_TAG, "Use passed in object as listener.");
		} else {
			Log.w(LOG_TAG, "No object passed in as listener. "
					+ "The activity itself need to be as listener.");
			try {
				mListener = (IListener) activity;
			} catch (ClassCastException e) {
				throw new ClassCastException(activity.toString()
						+ " need to prepare CListener object"
						+ " or implement Listener itself.");
			}
		}

		LogTraceLeave("onAttach");
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		LogTraceEnter("onCreate: " + savedInstanceState);
		super.onCreate(savedInstanceState);

		setRetainInstance(true);

		/* retrieve those values passed by activity in runtime */
		Bundle args = this.getArguments();
		if (args != null) {
			if (args.containsKey(KEY_ARG_REQCODE_RESOLVE_SIGNIN))
				mRCResolveSignIn = args.getInt(KEY_ARG_REQCODE_RESOLVE_SIGNIN);
			mAutoSignIn = args.getBoolean(KEY_ARG_AUTO_SIGNIN);
		}

		/* setup api client builder */
		GoogleApiClient.Builder builder = new GoogleApiClient.Builder(
				getActivity(), this, this);
		builder.addApi(Games.API);
		builder.addScope(Games.SCOPE_GAMES);
		builder.addApi(Plus.API);
		builder.addScope(Plus.SCOPE_PLUS_PROFILE);
		builder.addApi(AppStateManager.API);
		builder.addScope(AppStateManager.SCOPE_APP_STATE);
		mApiClient = builder.build();

		LogTraceLeave("onCreate");
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		LogTraceEnter("onCreateView: " + inflater + ", " + container + ", "
				+ savedInstanceState);
		LogTraceLeave("onCreateView");
		return null; /* we don't have any UI */
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		LogTraceEnter("onViewCreated: " + view + ", " + savedInstanceState);
		super.onViewCreated(view, savedInstanceState);
		/* Since we don't provide UI, this should not be called anyway. */
		LogTraceLeave("onViewCreated");
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		LogTraceEnter("onActivityCreated: " + savedInstanceState);
		super.onActivityCreated(savedInstanceState);
		LogTraceLeave("onActivityCreated");
	}

	@Override
	public void onStart() {
		LogTraceEnter("onStart");
		super.onStart();

		if (mAutoSignIn) {
			Log.d(LOG_TAG, "auto sign-in...");
			helperConnectToService(false);
		}

		LogTraceLeave("onStart");
	}

	@Override
	public void onResume() {
		LogTraceEnter("onResume");
		super.onResume();
		LogTraceLeave("onResume");
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		LogTraceEnter("onSaveInstanceState: " + outState);
		super.onSaveInstanceState(outState);
		LogTraceLeave("onSaveInstanceState");
	}

	@Override
	public void onPause() {
		LogTraceEnter("onPause");
		super.onPause();
		LogTraceLeave("onPause");
	}

	@Override
	public void onStop() {
		LogTraceEnter("onStop");
		super.onStop();
		helperDisconnectFromService(false);
		LogTraceLeave("onStop");
	}

	@Override
	public void onDestroyView() {
		LogTraceEnter("onDestroyView");
		super.onDestroyView();
		LogTraceLeave("onDestroyView");
	}

	@Override
	public void onDestroy() {
		LogTraceEnter("onDestroy");
		super.onDestroy();
		LogTraceLeave("onDestroy");
	}

	@Override
	public void onDetach() {
		LogTraceEnter("onDetach");
		super.onDetach();
		LogTraceLeave("onDetach");
	}

	// ===========================================================
	// Methods
	// ===========================================================
	public void beginUserInitiatedSignIn() {

		if (isHelperConnected()) {
			Log.w(LOG_TAG, "User requests sign in, but client is connected.");
			/* check why user sign in again when we already connected. */
			notifyHelperListener(Boolean.TRUE);
			return;
		}

		if (isHelperConnecting()) {
			Log.w(LOG_TAG, "User requests sign in, but client is connecting.");
			/* wait previous connection response and ignore this one */
			return;
		}

		helperConnectToService(true);
	}

	public void signOut() {

		helperDisconnectFromService(true);
	}

	public boolean requestShowAllLeaderboards() {

		boolean result = false;

		if (isHelperConnected()) {
			/*
			 * this request code is actually no use, put anything other than -1
			 * should be ok.
			 */
			startActivityForResult(
					Games.Leaderboards.getAllLeaderboardsIntent(mApiClient),
					mRCShowLeaderboard);
			result = true;
		}

		return result;
	}

	public boolean requestShowLeaderboard(final String pLeaderboardId) {

		boolean result = false;

		if (isHelperConnected()) {
			startActivityForResult(Games.Leaderboards.getLeaderboardIntent(
					mApiClient, pLeaderboardId), mRCShowLeaderboard);
			result = true;
		}

		return result;
	}

	/**
	 * Attempts to resolve a connection failure. This will usually involve
	 * starting a UI flow that lets the user give the appropriate consents
	 * necessary for sign-in to work.
	 * 
	 * @param pConnectionResult
	 *            The ConnectionResult that we're trying to resolve.
	 * @return True if resolvable (and later helper listener callback methods
	 *         should be invoked); False if not resolvable.
	 */
	private boolean resolveConnectionResult(
			final ConnectionResult pConnectionResult) {

		if (mWaitingActivityResult) {
			Log.w(LOG_TAG, "Help is still waiting for result "
					+ "from previous resolution. "
					+ "Will not to resolve another connection failure now");
			return false;
		}

		Log.d(LOG_TAG, "Try to resolveConnectionResult: " + pConnectionResult);
		boolean hasResolution = pConnectionResult.hasResolution();
		if (hasResolution) {
			Log.d(LOG_TAG, "Has resolution, starting it...");

			mWaitingActivityResult = true;
			try {
				pConnectionResult.startResolutionForResult(getActivity(),
						mRCResolveSignIn);
			} catch (SendIntentException e) {
				e.printStackTrace();
				mWaitingActivityResult = false;
				/* retry, need to make sure this won't fall in infinite loop */
				helperConnectToService(mDuringUserInitiateSignInFlow);
			}

		} else {
			Log.d(LOG_TAG, "No resolution. Abort!");
			/* do something */

		}

		return hasResolution;
	}

	/**
	 * Wrap api client to send connect request.
	 * 
	 * @param pUserInitiateSignIn
	 *            true when this connect request is initiated by user; false
	 *            when this connection request is initiated by auto sign in.
	 */
	private void helperConnectToService(final boolean pUserInitiateSignIn) {
		if (mApiClient != null) {

			if (isHelperConnected()) {
				Log.w(LOG_TAG, "Client had connected, skip connect again.");
				return;
			} else if (isHelperConnecting()) {
				Log.w(LOG_TAG,
						"Client and/or Helper is connecting, skip connect request");
				return;
			}

			Log.d(LOG_TAG, "Helper is connecting to service...");
			mConnecting = true;
			mDuringUserInitiateSignInFlow = pUserInitiateSignIn;
			mApiClient.connect();
		}
	}

	private void helperDisconnectFromService(final boolean pUserSignOut) {
		if (mApiClient != null) {
			if (isHelperConnected() || isHelperConnecting()) {
				Log.i(LOG_TAG, "Helper is diconnecting from service.");

				if (pUserSignOut) {
					Plus.AccountApi.clearDefaultAccount(mApiClient);
					Games.signOut(mApiClient);
					AppStateManager.signOut(mApiClient);
				}

				mApiClient.disconnect();
			} else
				Log.w(LOG_TAG, "Request helper to disconnect from service. "
						+ "But helper didn't connect to service.");
		}
	}

	private boolean isHelperConnected() {
		return mApiClient != null && mApiClient.isConnected();
	}

	private boolean isHelperConnecting() {
		boolean clientConnecting = (mApiClient != null && mApiClient
				.isConnecting());
		if (clientConnecting != mConnecting)
			Log.v(LOG_TAG, "Connecting state mismatch: Client["
					+ clientConnecting + "], Helper[" + mConnecting + "]");
		return clientConnecting || mConnecting;
	}

	private void setHelperConnected() {
		mConnecting = false;
		if (mDuringUserInitiateSignInFlow) {
			mDuringUserInitiateSignInFlow = false;
			mAutoSignIn = true;
		}
	}

	private void setHelperDisconnected() {
		mConnecting = false;
	}

	/**
	 * Notify listener the sign in result
	 * 
	 * @param success
	 *            True - sign-in result is succeed.<br>
	 *            False - Sign-in result is failed.<br>
	 *            null - Sign-in is cancelled.
	 */
	private void notifyHelperListener(Boolean success) {
		if (mListener == null)
			return;
		if (success == null)
			mListener.onSignInCanceled();
		else if (success == Boolean.TRUE)
			mListener.onSignInSucceed(mApiClient);
		else if (success == Boolean.FALSE)
			mListener.onSignInFailed();
	}

	private void LogTraceEnter(final String message) {
		if (BuildConfig.DEBUG) {
			Log.v(LOG_TAG, ">>> " + message);
		}
	}

	private void LogTraceLeave(final String message) {
		if (BuildConfig.DEBUG) {
			Log.v(LOG_TAG, "<<< " + message);
		}
	}

	private String getSHA1CertFingerprint(Context ctx) {
		try {
			Signature[] sigs = ctx.getPackageManager().getPackageInfo(
					ctx.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
			if (sigs.length == 0) {
				return "ERROR: NO SIGNATURE.";
			} else if (sigs.length > 1) {
				return "ERROR: MULTIPLE SIGNATURES";
			}
			byte[] digest = MessageDigest.getInstance("SHA1").digest(
					sigs[0].toByteArray());
			StringBuilder hexString = new StringBuilder();
			for (int i = 0; i < digest.length; ++i) {
				if (i > 0) {
					hexString.append(":");
				}
				byteToString(hexString, digest[i]);
			}
			return hexString.toString();

		} catch (PackageManager.NameNotFoundException ex) {
			ex.printStackTrace();
			return "(ERROR: package not found)";
		} catch (NoSuchAlgorithmException ex) {
			ex.printStackTrace();
			return "(ERROR: SHA1 algorithm not found)";
		}
	}

	private void byteToString(StringBuilder sb, byte b) {
		int unsigned_byte = b < 0 ? b + 256 : b;
		int hi = unsigned_byte / 16;
		int lo = unsigned_byte % 16;
		sb.append("0123456789ABCDEF".substring(hi, hi + 1));
		sb.append("0123456789ABCDEF".substring(lo, lo + 1));
	}

	private String getAppIdFromResource(Context ctx) {
		try {
			Resources res = ctx.getResources();
			String pkgName = ctx.getPackageName();
			int res_id = res.getIdentifier("app_id", "string", pkgName);
			return res.getString(res_id);
		} catch (Exception ex) {
			ex.printStackTrace();
			return "??? (failed to retrieve APP ID)";
		}
	}

	// ===========================================================
	// Inner and Anonymous Classes
	// ===========================================================
	public interface IListener {
		void onSignInSucceed(final GoogleApiClient pApiClient);

		void onSignInFailed();

		void onSignInCanceled();
	}

	public static abstract class CListener implements Parcelable, IListener {

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
		}

	}
}
