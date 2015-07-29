package timotheegroleau.com.grandmadream;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import android.preference.PreferenceManager;
import android.content.SharedPreferences;

import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.os.Handler;

import com.google.gdata.client.photos.PicasawebService;
import com.google.gdata.data.photos.AlbumEntry;
import com.google.gdata.data.photos.AlbumFeed;
import com.google.gdata.data.photos.PhotoData;
import com.google.gdata.data.photos.GphotoEntry;
import com.google.gdata.data.photos.GphotoFeed;
import com.google.gdata.data.photos.PhotoEntry;
import com.google.gdata.data.photos.UserFeed;
import com.google.gdata.util.ServiceException;
import com.google.gdata.util.ServiceForbiddenException;

import android.graphics.Point;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.GridLayout;
import android.widget.ImageView;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import java.util.Collections;


/**
 * Created by timothee on 18/7/15.
 */
public class GrandmaDream extends DreamService implements OnClickListener {

    // Not a dynamic service yet...
    private static final String SELECTED_ALBUM_GID = "6109397302746862529"; // bike ride airport
    // private static final String SELECTED_ALBUM_GID = "6161617020037391681"; // Mamie chassin Cadre Digital
    private static final int SHOW_NEXT_DELAY = 6000; // ms
    private static final double MIN_IMG_SHOW = 0.80; // 80%

    private static final String TAG = GrandmaDream.class.getSimpleName();

    private PicasawebService picasaService;
    AccountManager am;
    private Point screenSize;
    private ImageView img;
    private static final String API_PREFIX = "https://picasaweb.google.com/data/feed/api/user/";

    private List<PhotoEntry> photos = null;
    private int current_photo_index;

    private boolean stopped = false;

    private String selectedAccountName;
    private String selectedAuthToken;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        Log.d(TAG, "onAttachedToWindow");

        // Hide system UI
        try {
            // aggressive hiding of the menu bar
            View view = getWindow().getDecorView();
            view.setSystemUiVisibility(view.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LOW_PROFILE
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } catch (Exception e) {
            setFullscreen(true);
        }

        // Exit dream upon user touch
        setInteractive(true);
        // maintain full brightness
        setScreenBright(true);

        GridLayout ddLayout = new GridLayout(this);
        ddLayout.setColumnCount(1);
        ddLayout.setRowCount(1);

        screenSize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(screenSize);

        GridLayout.LayoutParams ddP = new GridLayout.LayoutParams();
        ddP.width = screenSize.x;
        ddP.height = screenSize.y;

        Log.d(TAG, "" + screenSize.x + " " + screenSize.y);

        // Log.d(TAG, screenSize.toString());
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        // img.setImageResource(R.drawable.test);
        img.setOnClickListener(this);

        ddLayout.addView(img, ddP);

        // Set the dream layout
        setContentView(ddLayout);

        startFrame();
    }

    private void startFrame() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
        selectedAccountName = preferences.getString("accountName", "");
        selectedAuthToken = preferences.getString("authToken", "");

        Log.d(TAG, "accountName: " + selectedAccountName);
        Log.d(TAG, "authToken: "   + selectedAuthToken);

        final String accountName = selectedAccountName;
        final String authToken = selectedAuthToken;

        picasaService = new PicasawebService("pictureframe");
        picasaService.setUserToken(authToken);

        new AsyncTask<Void, Void, List<PhotoEntry>>() {
            @Override
            protected List<PhotoEntry> doInBackground(Void... voids) {
                List<AlbumEntry> albums = null;
                try {
                    albums = getAlbums(accountName);
                    AlbumEntry album = albums.get(0);
                    Log.d(TAG, "Album name: " + album.getName() + "; gid: " + album.getGphotoId() + "; Album id: " + album.getId());

                    return getPhotos(accountName, album);
                } catch (ServiceForbiddenException e) {
                    Log.e(TAG, "Token expired, invalidating");
                    invalidate_and_renew_token();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
            protected void onPostExecute(List<PhotoEntry> result) {
                if (result == null) return;

                photos = result;
                current_photo_index = 0;
                showNext();
            }
        }.execute(null, null, null);
    }

    private void invalidate_and_renew_token() {
        am = (AccountManager) getSystemService(ACCOUNT_SERVICE);

        Account[] list = am.getAccounts();
        Account selectedAccount = null;

        for (Account a : list) {
            if (a.name.equals(selectedAccountName)) {
                Log.d(TAG, "Account found: " + a.name);
                selectedAccount = a;
                break;
            }
        }

        if (selectedAccount == null) {
            Log.d(TAG, "Unabel to find account");
        }

        am.invalidateAuthToken("com.google", selectedAuthToken);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("authToken");
        editor.commit();

        /*
        am.getAuthToken(
                selectedAccount,       // Account retrieved using getAccountsByType()
                "lh2",                 // Auth scope
                null,                  // Authenticator-specific options
                this,                  // Your activity ... meh, this is not an activity!
                new OnTokenAcquired(), // Callback called when a token is successfully acquired
                null);                 // Callback called if an error occ
        /**/
    }

    private class OnTokenAcquired implements AccountManagerCallback<Bundle> {
        @Override
        public void run(AccountManagerFuture<Bundle> result) {
            try {
                Bundle b = result.getResult();

                /*
                if (b.containsKey(AccountManager.KEY_INTENT)) {
                    Log.d(TAG, "KEY_INTENT detected, requesting again...");
                    Intent intent = b.getParcelable(AccountManager.KEY_INTENT);
                    int flags = intent.getFlags();
                    intent.setFlags(flags);
                    flags &= ~Intent.FLAG_ACTIVITY_NEW_TASK;
                    startActivityForResult(intent, REQUEST_AUTHENTICATE);
                    return;
                }
                /**/

                if (b.containsKey(AccountManager.KEY_AUTHTOKEN)) {
                    final String authToken = b.getString(AccountManager.KEY_AUTHTOKEN);

                    Log.d(TAG, "Auth token " + authToken);
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
                    SharedPreferences.Editor editor = preferences.edit();

                    editor.putString("authToken", authToken);
                    editor.commit();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void showNext() {
        if (stopped) return;
        if (photos == null) return;

        current_photo_index++;

        if (current_photo_index >= photos.size()) {
            Collections.shuffle(photos);
            current_photo_index = 0;
        }

        PhotoEntry photo = photos.get(current_photo_index);
        String imgURL = photo.getMediaContents().get(0).getUrl() + "?imgmax=1600";
        URL correctPhotoURL;

        try {
            correctPhotoURL = new URL(imgURL);
            Log.d(TAG, "Loading " + imgURL);
        }
        catch (MalformedURLException e) {
            scheduleNext();
            return;
        }

        final URL photoUrl = correctPhotoURL;

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try {
                    Bitmap bmp = BitmapFactory.decodeStream(photoUrl.openConnection().getInputStream());
                    return cropBest(bmp);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
            protected void onPostExecute(Bitmap bmp) {
                img.setImageBitmap(bmp);
                scheduleNext();
            }
        }.execute(null, null, null);
    }

    private void scheduleNext() {
        if (stopped) return;

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                showNext();
            }
        }, SHOW_NEXT_DELAY);
    }

    private Bitmap cropBest(final Bitmap bmp) {
        Bitmap cropped_bmp = bmp;
        Matrix scale_matrix = new Matrix();
        Boolean filtered = false;

        double screen_ratio = (double) screenSize.x / (double) screenSize.y;
        double bmp_ratio = (double) bmp.getWidth() / (double) bmp.getHeight();
        int min_num_pixels = (int) (bmp.getWidth() * bmp.getHeight() * MIN_IMG_SHOW);

        if (screen_ratio < bmp_ratio) {
            // needs horizontal crop to fit bmp height to screen height
            int cropped_width = (int) (bmp.getHeight() * screen_ratio);
            int min_width = min_num_pixels / bmp.getHeight();

            if (cropped_width < min_width) {
                // cropped_width is cropping more than we want to allow, reduce crop
                cropped_width = min_width;
            }

            if (cropped_width < screenSize.x) {
                float scale = (float) screenSize.x / (float) cropped_width;
                scale_matrix.setScale(scale, scale);
                filtered = true;
            }

            cropped_bmp = Bitmap.createBitmap(bmp,
                    (bmp.getWidth() - cropped_width) / 2,
                    0,
                    cropped_width,
                    bmp.getHeight(),
                    scale_matrix,
                    filtered
            );
        }
        else if (screen_ratio > bmp_ratio) {
            // needs vertical crop to fit bmp width to screen width
            int cropped_height = (int) (bmp.getWidth() * screen_ratio);
            int min_height = min_num_pixels / bmp.getWidth();

            if (cropped_height < min_height) {
                // cropped_height is cropping more than we want to allow, reduce crop
                cropped_height = min_height;
            }

            if (cropped_height < screenSize.y) {
                float scale = (float) screenSize.y / (float) cropped_height;
                scale_matrix.setScale(scale, scale);
                filtered = true;
            }

            cropped_bmp = Bitmap.createBitmap(bmp,
                    0,
                    (bmp.getHeight() - cropped_height) / 2,
                    bmp.getWidth(),
                    cropped_height,
                    scale_matrix,
                    filtered
            );
        }
        else {
            if (bmp.getWidth() < screenSize.x) {
                float scale = (float) screenSize.x / (float) bmp.getWidth();
                scale_matrix.setScale(scale, scale);

                cropped_bmp = Bitmap.createBitmap(bmp,
                        0,
                        0,
                        bmp.getWidth(),
                        bmp.getHeight(),
                        scale_matrix,
                        true
                );
            }
        }

        Log.d(TAG, "Original image size: " + bmp.getWidth() + 'x' + bmp.getHeight());
        Log.d(TAG, "Cropped image size: " + cropped_bmp.getWidth() + 'x' + cropped_bmp.getHeight());

        return cropped_bmp;
    }

    @Override
    public void onDetachedFromWindow() {
        // tidy up
        img.setOnClickListener(null);

        super.onDetachedFromWindow();
    }

    public void onClick(View v){
        stopped = true;
        this.finish();
    }

    public <T extends GphotoFeed> T getFeed(String feedHref,
                                            Class<T> feedClass) throws IOException, ServiceException {
        Log.d(TAG, "Get Feed. URL: " + feedHref);
        return picasaService.getFeed(new URL(feedHref), feedClass);
    }

    public List<AlbumEntry> getAlbums(String userId) throws IOException,
            ServiceException {

        String userFeedUrl = API_PREFIX + userId;
        Log.d(TAG, "Get Album. URL: " + userFeedUrl);
        UserFeed userFeed = getFeed(userFeedUrl, UserFeed.class);

        List<GphotoEntry> entries = userFeed.getEntries();
        List<AlbumEntry> albums = new ArrayList<AlbumEntry>();
        for (GphotoEntry entry : entries) {

            AlbumEntry ae = new AlbumEntry(entry);
            if (!ae.getGphotoId().equals(SELECTED_ALBUM_GID)) continue;

            Log.d(TAG, "Found Album: " + ae.getName());
            albums.add(ae);
            break;
        }

        Collections.shuffle(albums);

        return albums;
    }

    public List<PhotoEntry> getPhotos(String userId, AlbumEntry album) throws IOException,
            ServiceException{
        AlbumFeed feed = album.getFeed(PhotoData.KIND);
        int left = 2;
        List<PhotoEntry> photos = new ArrayList<PhotoEntry>();
        for (GphotoEntry entry : feed.getEntries()) {
            PhotoEntry pe = new PhotoEntry(entry);
            photos.add(pe);
        }
        Log.d(TAG, "Album " + album.getName() + " has " + photos.size() + " photos");

        Collections.shuffle(photos);

        return photos;
    }
}