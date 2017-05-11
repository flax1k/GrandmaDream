package timotheegroleau.com.grandmadream;

import android.util.Log;

import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.os.Handler;

import java.util.Arrays;
import java.security.SecureRandom;

import android.graphics.Point;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.GridLayout;
import android.widget.ImageView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by timothee on 18/7/15.
 */
public class GrandmaDream extends DreamService implements OnClickListener {
    // Not a dynamic service yet...
    private static final String sharedAlbumUrl = "https://photos.google.com/share/AF1QipNwbarcShrqZ0EkwsuvFvG9xcSoF8bRJOa0O6ixKbQxhLEk7u_YvRQlmZjQAlDhHg?key=cmFxU0RTOHg3M0FfU1BkT0ZickhES0E2aXNmQUZn";

    public enum Mode { ONLINE, LOCAL }

    private static final int SHOW_NEXT_DELAY = 45 * 1000; // ms
    private static final double MIN_IMG_SHOW = 0.75; // 75%

    private static final String TAG = GrandmaDream.class.getSimpleName();

    private SecureRandom random = new SecureRandom(); // probably overkill, but am trying to get really good uniform random distribution, Math.random() is giving me too many repeats of particular value range for some reason :/

    private Point screenSize;
    private ImageView img;

    private List<String> photos = null;
    private int current_photo_index;

    private Mode mode;
    private boolean stopped = false;

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

        Log.d(TAG, "Screensize: " + screenSize.toString());

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        img.setOnClickListener(this);

        ddLayout.addView(img, ddP);

        // Set the dream layout
        setContentView(ddLayout);

        // Let's go
        refreshPhotos();
    }

    private void refreshPhotos() {
        Log.d(TAG, "refreshPhotos()");
        new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... voids) {
                try {
                    List<String> photoUrls = getPhotoUrlsFromPublicSharedAlbum(sharedAlbumUrl);

                    if (photoUrls.size() <= 0) {
                        throw new Exception("Album unexpectedly empty");
                    }

                    mode = Mode.ONLINE;
                    return photoUrls;
                }
                catch (Exception e) {
                    mode = Mode.LOCAL;
                    return Arrays.asList(fileList());
                }
            }
            protected void onPostExecute(List<String> list) {
                if (list == null || list.size() <= 0) {
                    if (photos == null) {
                        finish();
                        return;
                    }
                }
                else {
                    photos = list;
                }

                current_photo_index = 0;
                showNext();
            }
        }.execute(null, null, null);
    }

    // return non-linear indexes from a set
    // we convert a discreet list into a continuous function
    // args:
    //   - p1: probability of picking first element
    //   - pn: probability of picking last element n
    //   - n: number of element in the list
    public int getIndex(double p1, double pn, double n) {
        double a, b, c;

        // only one element in the list
        if (n == 1) return 0;

        // same weight for each entry
        if (p1 == pn || mode == Mode.LOCAL) return (int) Math.floor(random.nextDouble() * n);

        // we have a slope for the weights of each entry, do some math magic!

        // line coefficients
        // we're working with 2 linear equations:
        //    - p1 = a * 0.5 + b
        //    - pn = a * (n - 0.5) + b

        a = (p1-pn)/(1-n);
        b = p1 - a * 0.5;

        // quadratic coefficients from integration
        //     - y = ax + b   =>   y = a/2x^2 + bx + c
        a /= 2;
        b = b;
        c = 0;

        // determine range of y by computing largest integral value at n
        // note: the integral is increasing regardless of whether the probably weight slope was downwards OR upwards
        double max = a * n * n + b * n + c;
        double r = random.nextDouble();
        double y = r * max;

        c -= y; // so we can apply the quadratic formula ax^2 + bx + c = 0

        double val = (-b + Math.sqrt(b*b - 4*a*c))/(2*a);
        int index = (int) Math.floor(val);

        Log.d(TAG, "getIndex: coefs: " + a + ", " + b + ", " + c);
        Log.d(TAG, "getIndex: vals: " + r + ", " + max + ", " + index + "/" + n);

        return index;
    }

    public void showNext() {
        if (stopped) return;
        if (photos == null) return;

        Log.d(TAG, "showNext()");

        if (current_photo_index++ >= photos.size()) {
            refreshPhotos();
            return;
        }

        int index = getIndex(1.0, 2.0, (double) photos.size());

        String imgURL = photos.get(index);
        String temp_filename;
        URL temp_photoUrl = null;

        Log.d(TAG, "Loading " + imgURL);

        if (mode == Mode.LOCAL) {
            temp_filename = imgURL;
        }
        else {
            try {
                temp_photoUrl = new URL(imgURL);
            } catch (MalformedURLException e) {
                e.printStackTrace();
                scheduleNext();
                return;
            }

            temp_filename = temp_photoUrl.getFile().substring(1);
        }

        final String filename = temp_filename;
        final URL photoUrl = temp_photoUrl;

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                int tries = 2;

                do {
                    Bitmap bmp;

                    try {
                        FileInputStream in = openFileInput(filename);
                        Log.d(TAG, "Reading image from file: " + filename);
                        bmp = BitmapFactory.decodeStream(in);
                        in.close();
                        return cropBest(bmp);
                    }
                    catch(FileNotFoundException e_file_read) {
                        InputStream url_in;
                        FileOutputStream fout = null;

                        try {
                            url_in = photoUrl.openStream();
                        }
                        catch(Exception e) {
                            return null;
                        }

                        try {
                            Log.d(TAG, "Fetching image from source: " + photoUrl + " and caching as " + filename);
                            fout = openFileOutput(filename, getApplicationContext().MODE_PRIVATE);
                            IOUtils.copy(url_in, fout);
                            fout.close();
                            continue;
                        }
                        catch(IOException e_file_not_found) { // captures FileNotFoundException too since it's a subclass
                            try {
                                // Try to read URL directly into bitmap
                                bmp = BitmapFactory.decodeStream(url_in);
                                return cropBest(bmp);
                            } catch (Exception e) {
                                e.printStackTrace();
                                return null;
                            }
                        }
                        finally {
                            try { url_in.close(); } catch (Exception e) {}
                            try { fout  .close(); } catch (Exception e) {}
                        }
                    }
                    catch(Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                }
                while (--tries > 0);

                return null;
            }
            protected void onPostExecute(Bitmap bmp) {
                if (img != null) img.setImageBitmap(bmp);
                scheduleNext();
            }
        }.execute(null, null, null);
    }

    private void scheduleNext() {
        if (stopped) return;

        Log.d(TAG, "scheduleNext()");
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

        if (screen_ratio < bmp_ratio) {
            Log.d(TAG, "Horizontal crop necessary");

            // needs horizontal crop to fit bmp height to screen height
            int cropped_width = (int) (bmp.getHeight() * screen_ratio);
            int min_width = (int) (bmp.getWidth() * MIN_IMG_SHOW);

            if (cropped_width < min_width) {
                // cropped_width is cropping more than we want to allow, reduce crop
                cropped_width = min_width;
            }

            if (cropped_width < screenSize.x) {
                float scale = (float) screenSize.x / (float) cropped_width;
                Log.d(TAG, "Scaling necessary: " + scale);
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
            int cropped_height = (int) (bmp.getWidth() / screen_ratio);
            int min_height = (int) (bmp.getHeight() * MIN_IMG_SHOW);

            Log.d(TAG, "Vertical crop necessary, cropped height: " + cropped_height + "; min_height: " + min_height);

            if (cropped_height < min_height) {
                // cropped_height is cropping more than we want to allow, reduce crop
                cropped_height = min_height;
            }

            if (cropped_height < screenSize.y) {
                float scale = (float) screenSize.y / (float) cropped_height;
                Log.d(TAG, "Scaling necessary: " + scale);
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
            Log.d(TAG, "No cropping necessary");
            if (bmp.getWidth() < screenSize.x) {
                Log.d(TAG, "Upscale necessary");
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
        // Log.d(TAG, "Screen size: " + screenSize.x + 'x' + screenSize.y);
        Log.d(TAG, "Cropped image size: " + cropped_bmp.getWidth() + 'x' + cropped_bmp.getHeight());

        return cropped_bmp;
    }

    @Override
    public void onDetachedFromWindow() {
        // tidy up
        Log.d(TAG, "onDetachedFromWindow");
        img.setOnClickListener(null);
        stopped = true;

        super.onDetachedFromWindow();
    }

    public void onClick(View v) {
        Log.d(TAG, "onClick");
        stopped = true;
        finish();
    }

    public List<String> getPhotoUrlsFromPublicSharedAlbum(String url) throws MalformedURLException, IOException {
        String content = IOUtils.toString(new URL(url));

        List<String> URLs = new ArrayList<String>();

        // Create a Pattern object (?s) is to ac tivate PATTERN.DOTALL
        // Warning: This is a disgusting hack that reads data out of a javascript POJO sctructure with regex (pls dont vomit)
        //          It is VERY fragile, and will stop functionning if google changes the way their shared albums are delivered
        Matcher m1 = Pattern.compile("(?s)AF_initDataCallback\\((.+)\\}\\]\\n\\]").matcher(content);

        if (!m1.find()) {
            // unexpected format, need to report somewhere?
            Log.d(TAG, "getPhotoUrlsFromPublicSharedAlbum: no url block match in content");
            // TODO: return a list of files from files in local cache
            return URLs;
        }

        Matcher m2 = Pattern.compile("\"(https://[^\"]+)\",(\\d+),(\\d+),").matcher(m1.group(1)); // 1.url, 2.width, 3.height

        while (m2.find()) {
            String imgUrl = m2.group(1) + "=w" + m2.group(2) + "-h" + m2.group(3) + "-no";

            URLs.add(imgUrl);
        }

        if (URLs.size() <= 0) {
            Log.d(TAG, "getPhotoUrlsFromPublicSharedAlbum: no img match in content");
        }

        // Fix chronological order to be the same as order in getPhotoUrls
        Log.d(TAG, "getPhotoUrlsFromPublicSharedAlbum: acquired " + URLs.size() + " photos");

        return URLs;
    }
}