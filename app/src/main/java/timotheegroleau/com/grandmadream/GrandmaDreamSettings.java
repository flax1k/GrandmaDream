package timotheegroleau.com.grandmadream;

import android.content.Intent;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;

import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.AccountPicker;

import android.preference.PreferenceManager;
import android.content.SharedPreferences;

import android.widget.Toast;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.util.Log;

public class GrandmaDreamSettings extends AppCompatActivity {

    private static final String TAG = GrandmaDreamSettings.class.getSimpleName();

    private static final int PICK_ACCOUNT_REQUEST = 1;
    private static final int REQUEST_AUTHENTICATE = 2;

    AccountManager am;
    Account[] list;
    String selectedAccountName;
    Account selectedAccount;

    SharedPreferences preferences;
    SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "GrandmaDreamSettings");
        super.onCreate(savedInstanceState);

        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editor = preferences.edit();

        am = (AccountManager) getSystemService(ACCOUNT_SERVICE);

        setContentView(R.layout.activity_grandma_dream_settings);

        int status = GooglePlayServicesUtil.isGooglePlayServicesAvailable(getApplicationContext());
        if (status != ConnectionResult.SUCCESS)
        {
            Log.e(TAG, String.valueOf(status));
            Toast.makeText(this, "Google Play Services missing", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Log.d(TAG, "Google Play Services available");

        Intent intent = AccountPicker.newChooseAccountIntent(null, null, new String[]{"com.google"},
                false, null, null, null, null);
        startActivityForResult(intent, PICK_ACCOUNT_REQUEST);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch(requestCode) {
            case PICK_ACCOUNT_REQUEST:
                if (resultCode == RESULT_OK) {
                    selectedAccountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

                    Log.d(TAG, "Account Name=" + selectedAccountName);

                    editor.putString("accountName", selectedAccountName);
                    editor.commit();

                    list = am.getAccounts();

                    for (Account a : list) {
                        Log.d(TAG, "Account iteration: " + a.name);
                        if (a.name.equals(selectedAccountName)) {
                            Log.d(TAG, "Account found: " + a.name);
                            selectedAccount = a;
                            break;
                        }
                    }

                    Log.d(TAG, "Requesting Oauth token");
                    am.getAuthToken(
                            selectedAccount,       // Account retrieved using getAccountsByType()
                            "lh2",                 // Auth scope
                            null,                  // Authenticator-specific options
                            this,                  // Your activity
                            new OnTokenAcquired(), // Callback called when a token is successfully acquired
                            null);                 // Callback called if an error occ
                }

                break;

            case REQUEST_AUTHENTICATE:
                if (resultCode == RESULT_OK) {
                    am.getAuthToken(
                            selectedAccount,       // Account retrieved using getAccountsByType()
                            "lh2",                 // Auth scope
                            null,                  // Authenticator-specific options
                            this,                  // Your activity
                            new OnTokenAcquired(), // Callback called when a token is successfully acquired
                            null);                 // Callback called if an error occ
                }
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_grandma_dream_settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private class OnTokenAcquired implements AccountManagerCallback<Bundle> {
        @Override
        public void run(AccountManagerFuture<Bundle> result) {
            Log.d(TAG, "AccountManagerCallback");

            try {
                Bundle b = result.getResult();

                if (b.containsKey(AccountManager.KEY_INTENT)) {
                    Log.d(TAG, "KEY_INTENT detected, requesting again...");
                    Intent intent = b.getParcelable(AccountManager.KEY_INTENT);
                    int flags = intent.getFlags();
                    intent.setFlags(flags);
                    flags &= ~Intent.FLAG_ACTIVITY_NEW_TASK;
                    startActivityForResult(intent, REQUEST_AUTHENTICATE);
                    return;
                }

                if (b.containsKey(AccountManager.KEY_AUTHTOKEN)) {
                    final String authToken = b.getString(AccountManager.KEY_AUTHTOKEN);

                    Log.d(TAG, "Auth token " + authToken);
                    editor.putString("authToken", authToken);
                    editor.commit();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
