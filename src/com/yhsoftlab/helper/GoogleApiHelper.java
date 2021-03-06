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
import com.google.android.gms.appstate.AppStateManager.StateResult;
import com.google.android.gms.appstate.AppStateStatusCodes;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
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
	public final static String KEY_ARG_LISTENER_IMPL = "ListenerImpl";
	public final static String KEY_ARG_AUTO_SIGNIN = "AutoSignIn";

	/* argument values */
	public final static int VAL_ARG_yy = 1;

	/* request codes */
	private final int REQCODE_RESOLVE_SIGNIN_DEFAULT = 10001;

	// ===========================================================
	// Fields
	// ===========================================================
	private GoogleApiClient mApiClient = null;
	private IListener mListener = null;
	/* Allow activity overwrite this value to avoid conflict. */
	private int mRCResolveSignIn = REQCODE_RESOLVE_SIGNIN_DEFAULT;
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

	private ResultCallback<AppStateManager.StateResult> mLoadResult = new ResultCallback<AppStateManager.StateResult>() {

		@Override
		public void onResult(StateResult result) {
			AppStateManager.StateLoadedResult loadedResult = result
					.getLoadedResult();
			AppStateManager.StateConflictResult conflictResult = result
					.getConflictResult();
			if (loadedResult != null) {
				int statusCode = loadedResult.getStatus().getStatusCode();
				switch (statusCode) {
				case AppStateStatusCodes.STATUS_OK:
					if (mListener != null)
						mListener.onCloudSaveDataLoaded(
								loadedResult.getStateKey(),
								loadedResult.getLocalData());
					break;
				case AppStateStatusCodes.STATUS_STATE_KEY_NOT_FOUND:
					Log.e(LOG_TAG, "The requested state key was not found.");
					if (mListener != null)
						mListener.onCloudSaveDataNotFound(loadedResult
								.getStateKey());
					break;
				case AppStateStatusCodes.STATUS_STATE_KEY_LIMIT_EXCEEDED:
					/* should not happened in our implementation */
					Log.e(LOG_TAG,
							"The application already has data in the maximum number of keys (data slots) and is attempting to create a new one.");
					break;
				case AppStateStatusCodes.STATUS_CLIENT_RECONNECT_REQUIRED:
					Log.e(LOG_TAG,
							"The GoogleApiClient is in an inconsistent state and must reconnect to the service to resolve the issue.");
					helperConnectToService(false);
					break;
				case AppStateStatusCodes.STATUS_INTERNAL_ERROR:
					Log.e(LOG_TAG,
							"An unspecified error occurred; no more specific information is available.");
					break;
				case AppStateStatusCodes.STATUS_NETWORK_ERROR_STALE_DATA:
					Log.e(LOG_TAG,
							"A network error occurred while attempting to retrieve fresh data, but some locally cached data was available.");
					break;
				case AppStateStatusCodes.STATUS_NETWORK_ERROR_NO_DATA:
					Log.e(LOG_TAG,
							"A network error occurred while attempting to retrieve fresh data, and no data was available locally.");
					break;
				case AppStateStatusCodes.STATUS_DEVELOPER_ERROR:
					Log.e(LOG_TAG,
							"Your application is incorrectly configured.");
					break;
				case AppStateStatusCodes.STATUS_INTERRUPTED:
					Log.e(LOG_TAG,
							"Was interrupted while waiting for the result.");
					break;
				case AppStateStatusCodes.STATUS_TIMEOUT:
					Log.e(LOG_TAG,
							"The operation timed out while awaiting the result.");
					break;
				default:
					break;
				}
			} else if (conflictResult != null) {
				if (mListener != null)
					mListener.onCloudSaveDataConflicted(
							conflictResult.getStateKey(),
							conflictResult.getResolvedVersion(),
							conflictResult.getServerData(),
							conflictResult.getLocalData());
			}
		}
	};

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

		if (requestCode == mRCResolveSignIn + 1) { /* don't care this result */
			Log.i(LOG_TAG, "Result of this request is skipped intentionally.");
			return;
		} else if (requestCode != mRCResolveSignIn) {
			Log.w(LOG_TAG, "This is not expected result. "
					+ "If you do not want this result is missed by other, "
					+ "you would like to check why we receive this result.");
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
					mRCResolveSignIn + 1);
			result = true;
		}

		return result;
	}

	public boolean requestShowLeaderboard(final String pLeaderboardId) {

		boolean result = false;

		if (isHelperConnected()) {
			startActivityForResult(Games.Leaderboards.getLeaderboardIntent(
					mApiClient, pLeaderboardId), mRCResolveSignIn + 1);
			result = true;
		}

		return result;
	}

	public boolean requestShowAchievements() {

		boolean result = false;
		if (isHelperConnected()) {

			startActivityForResult(
					Games.Achievements.getAchievementsIntent(mApiClient),
					mRCResolveSignIn + 1);
			result = true;
		}
		return result;
	}

	public boolean achievementIncrement(int pAchievementId, int numSteps) {
		return achievementIncrement(getString(pAchievementId), numSteps);
	}

	public boolean achievementIncrement(String pAchievementId, int numSteps) {

		boolean result = false;
		if (isHelperConnected()) {
			Games.Achievements.increment(mApiClient, pAchievementId, numSteps);
			result = true;
		}
		return result;
	}

	public boolean achievementSetSteps(int pAchievementId, int numSteps) {
		return achievementSetSteps(getString(pAchievementId), numSteps);
	}

	public boolean achievementSetSteps(String pAchievementId, int numSteps) {

		boolean result = false;
		if (isHelperConnected()) {
			Games.Achievements.setSteps(mApiClient, pAchievementId, numSteps);
			result = true;
		}
		return result;
	}

	public boolean achievementUnlock(int pAchievementId) {
		return achievementUnlock(getString(pAchievementId));
	}

	public boolean achievementUnlock(String pAchievementId) {

		boolean result = false;
		if (isHelperConnected()) {
			Games.Achievements.unlock(mApiClient, pAchievementId);
			result = true;
		}
		return result;
	}

	public boolean saveToCloud(int stateKey, byte[] data) {
		return saveToCloud(true, stateKey, data);
	}

	public boolean saveToCloud(boolean async, int stateKey, byte[] data) {
		boolean handled = false;

		if (isHelperConnected()) {
			if (stateKey >= 0
					&& stateKey < AppStateManager.getMaxNumKeys(mApiClient)
					&& data.length <= AppStateManager
							.getMaxStateSize(mApiClient)) {
				if (async)
					AppStateManager.update(mApiClient, stateKey, data);
				else
					AppStateManager.updateImmediate(mApiClient, stateKey, data);
				handled = true;
			}
		}
		return handled;
	}

	/**
	 * Load data from cloud save. The data will be loaded asynchronously, and
	 * the result will return back to caller through listener interface methods.
	 * 
	 * @param stateKey
	 */
	public boolean loadFromCloud(int stateKey) {

		boolean handled = false;

		if (isHelperConnected()) {
			if (stateKey >= 0
					&& stateKey < AppStateManager.getMaxNumKeys(mApiClient)) {
				AppStateManager.load(mApiClient, stateKey).setResultCallback(
						mLoadResult);
				handled = true;
			}
		}

		return handled;
	}

	/**
	 * Application needs to call this function to mark conflict is resolved
	 * after onCloudSaveDataConflict is invoked.
	 * 
	 * @param stateKey
	 * @param resolvedVersion
	 * @param resolvedData
	 */
	public void conflictResolvedInCloudSave(int stateKey,
			String resolvedVersion, byte[] resolvedData) {
		AppStateManager.resolve(mApiClient, stateKey, resolvedVersion,
				resolvedData).setResultCallback(mLoadResult);
	}

	public boolean leaderboardSubmitScore(int pLeaderboardId, long pRawScore) {
		return leaderboardSubmitScore(getString(pLeaderboardId), pRawScore);
	}

	public boolean leaderboardSubmitScore(String pLeaderboardId, long pRawScore) {

		boolean handled = false;
		if (isHelperConnected()) {
			Games.Leaderboards.submitScore(mApiClient, pLeaderboardId,
					pRawScore);
			handled = true;
		}
		return handled;
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
				Log.w(LOG_TAG, "Client had connected, reconnecting client.");
				mApiClient.reconnect();
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
		/* Sign-in results */
		void onSignInSucceed(final GoogleApiClient pApiClient);

		void onSignInFailed();

		void onSignInCanceled();

		/* Cloud save data load result */
		void onCloudSaveDataLoaded(int stateKey, byte[] pLocalData);

		void onCloudSaveDataNotFound(int stateKey);

		void onCloudSaveDataConflicted(int stateKey, String resolvedVersion,
				byte[] pServerData, byte[] pLocalData);
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
