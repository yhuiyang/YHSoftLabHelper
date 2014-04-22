YHSoftLabHelper
===============


GoogleApiHelper
---------------

A wrap helper, helps activity to manage GoogleApiClient.
Usage:

    public class MainActivity extends FragmentActivity {
        ...
        
        private final String TAG_GOOGLE_API_HELPER = "GAHelper";
        private int RC_RESOLVE_SIGNIN = 9001;
        private GoogleApiHelper mHelper;
        
        private final GoogleApiHelper.CListener mHelperListener = new GoogleApiHelper.CListener() {
            @Override
            public void onSignInSucceed(final GoogleApiClient pClient) {
            }
            @Override
            public void onSignInFailed() {
            }
            /* ... you need to implement all the interface methods here ... */
        };
        
        @Override
        public void onCreate(Bundle pSavedInstanceState) {
            super.onCreate(pSavedInstanceState);
            
            ...
            
            mHelper = (GoogleApiHelper) getSupportFragmentManager().findFragmentByTag(TAG_GOOGLE_API_HELPER);
            if (mHelper == null) {
                Bundle args = new Bundle();
                args.putInt(GoogleApiHelper.KEY_ARG_REQCODE_RESOLVE_SIGNIN, RC_RESOLVE_SIGNIN);
                args.putParcelable(GoogleApiHelper.KEY_ARG_LISTENER_IMPL, mHelperListener);
                /* add other helper parameters here. ex: client flags */
                mHelper = (GoogleApiHelper) Fragment.instantiate(this, GoogleApiHelper.class.getName(), args);
                getSupportFragmentManager().beginTransaction().add(mGoogleApiHelper, TAG_GOOGLE_API_HELPER).commit();
            }
        }
        
        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == RC_RESOLVE_SIGNIN) {
                mHelper.onActivityResult(request, resultCode, data);
                return;
            }
            super.onActivityResult(requestCode, resultCode, data);
        }
    
    }
