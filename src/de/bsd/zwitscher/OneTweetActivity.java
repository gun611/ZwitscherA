package de.bsd.zwitscher;


import android.app.ActionBar;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.google.api.translate.Language;
import com.google.api.translate.Translate;

import de.bsd.zwitscher.account.Account;
import de.bsd.zwitscher.account.AccountHolder;
import de.bsd.zwitscher.helper.NetworkHelper;
import de.bsd.zwitscher.helper.PicHelper;
import de.bsd.zwitscher.helper.UrlPair;
import twitter4j.MediaEntity;
import twitter4j.Place;
import twitter4j.Status;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.util.Log;
import android.view.View;
import twitter4j.URLEntity;
import twitter4j.User;
import twitter4j.UserMentionEntity;

import java.io.BufferedInputStream;
import java.net.URL;
import java.util.*;

/**
 * This Activity displays one individual status.
 * Layout definition is in res/layout/single_tweet
 *
 * @author Heiko W. Rupp
 */
public class OneTweetActivity extends Activity implements OnInitListener, OnUtteranceCompletedListener {

	private Status status ;
    private ImageView userPictureView;
    private ProgressBar pg;
    private boolean downloadPictures=false;
    private TextToSpeech tts;
    private TextView titleTextView;
    private Account account;

    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        if (android.os.Build.VERSION.SDK_INT<11) {
            requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
		    setContentView(R.layout.single_tweet);
            getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.window_title);
            pg = (ProgressBar) findViewById(R.id.title_progress_bar);
            titleTextView = (TextView) findViewById(R.id.title_msg_box);
        }
        else
            setContentView(R.layout.single_tweet_honeycomb);


        userPictureView = (ImageView) findViewById(R.id.UserPictureImageView);

        NetworkHelper networkHelper = new NetworkHelper(this);
        downloadPictures = networkHelper.mayDownloadImages();

        Intent intent = getIntent();
        String dataString = intent.getDataString();
        Bundle bundle = intent.getExtras();
        account = AccountHolder.getInstance().getAccount();

        // If this is not null, we are called from another app
        if ( dataString!=null) {
            // Lets see what server type that is and get an account for it
            // so that we can access the data
            if (dataString.contains("://twitter.com")) {
                TweetDB tmp = TweetDB.getInstance(getApplicationContext());
                account = tmp.getAccountForType("twitter");
            }
            else if (dataString.contains("://identi.ca")) {
                TweetDB tmp = TweetDB.getInstance(getApplicationContext());
                account = tmp.getAccountForType("identi.ca");

            }
            else {
                // TODO for e.g. generic status.net
            }

            if (dataString.matches("http://twitter.com/.*/status/.*$")) {
                String tids = dataString.substring(dataString.lastIndexOf("/")+1);
                Long tid = Long.parseLong(tids);
                TwitterHelper th = new TwitterHelper(this, account);
                status = th.getStatusById(tid,0L,false,false);
            } else if (dataString.matches("http://twitter.com/#!/.*$")) {
                // A user - forward to UserDetailActivity TODO: remove once this is coded in AndroidManifest.xml
                String userName = dataString.substring(dataString.lastIndexOf("/")+1);
                // TODO account needs to be a twitter one

                Intent i = new Intent(this,UserDetailActivity.class);
                i.putExtra("userName",userName);
                startActivity(i);
                finish();
            } else if (dataString.matches("http://twitter.com/[a-zA-Z0-9_]*\\?.*")) {
                // A user ref in email - forward to UserDetailActivity TODO: remove once this is coded in AndroidManifest.xml
                // TODO account needs to be a twitter one

                String userName = dataString.substring(19,dataString.indexOf("?"));
                Intent i = new Intent(this,UserDetailActivity.class);
                i.putExtra("userName",userName);
                startActivity(i);
                finish();
            }

        } else {
            // Called from within Zwitscher
            status = (Status) bundle.get(getString(R.string.status));
        }


        if (status==null) {
            // e.g. when called from HTC Mail, which fails to forward the full
            // http://twitter.com/#!/.../status/.. url, but only sends http://twitter.com
            Log.w("OneTweetActivity","Status was null for Intent " + intent );
            if (tts!=null)
                tts.shutdown();

            finish();
            return;
        }

        Log.i("OneTweetActivity","Showing status: " + status.toString());

        // Download the user profile image in a background task, as this may
        // mean a network call.
        if (status.getRetweetedStatus()==null)
            new DownloadUserImageTask().execute(status.getUser());
        else
            new DownloadUserImageTask().execute(status.getRetweetedStatus().getUser());

        // Check if the tweet contains urls to picture services and load thumbnails
        // if needed
        new DownloadImagePreviewsTask(this, status).execute();

        TextView tv01 = (TextView) findViewById(R.id.TextView01);
        StringBuilder sb = new StringBuilder("<b>");
        if (status.getRetweetedStatus()==null) {
            sb.append(status.getUser().getName());
            sb.append(" (");
            sb.append(status.getUser().getScreenName());
            sb.append(")");
            sb.append("</b>");
        }
        else {
            sb.append(status.getRetweetedStatus().getUser().getName());
            sb.append(" (");
            sb.append(status.getRetweetedStatus().getUser().getScreenName());
            sb.append(" )</b> ").append(getString(R.string.resent_by)).append(" <b>");
            sb.append(status.getUser().getName());
            sb.append("</b>");
        }
        tv01.setText(Html.fromHtml(sb.toString()));

        TextView mtv = (TextView) findViewById(R.id.MiscTextView);
        if (status.getInReplyToScreenName()!=null) {
            String s = getString(R.string.in_reply_to);
            mtv.setText(Html.fromHtml(s + " <b>" + status.getInReplyToScreenName() + "</b>"));
        }
        else {
            mtv.setText("");
        }

        TextView tweetView = (TextView)findViewById(R.id.TweetTextView);
//        tweetView.setText(status.getText());
        setTweetText(tweetView);

        TextView timeClientView = (TextView)findViewById(R.id.TimeTextView);
        TwitterHelper th = new TwitterHelper(this, account);
        String s = getString(R.string.via);
        String text = th.getStatusDate(status) + s + status.getSource();
        String from = getString(R.string.from);
        if (status.getPlace()!=null) {
            Place place = status.getPlace();
            text += " " + from + " " ;
            if (place.getFullName()!=null)
                text += "<a href=\"geo:0,0?q=" + place.getFullName() + ","+ place.getCountry() + "\">";
            text += place.getFullName();
            if (place.getFullName()!=null)
                text += "</a>";

        }
        timeClientView.setText(Html.fromHtml(text));
        timeClientView.setMovementMethod(LinkMovementMethod.getInstance());


        // Update Button state depending on Status' properties
        ImageButton threadButton = (ImageButton) findViewById(R.id.ThreadButton);
        if (status.getInReplyToScreenName()==null) {
            if (threadButton!=null)  // TODO Honeycomb?
                threadButton.setEnabled(false);
        }

        ImageView favoriteButton = (ImageView) findViewById(R.id.FavoriteButton);
        if (favoriteButton!=null) {
            if (status.isFavorited())
                favoriteButton.setImageResource(R.drawable.favorite_on);
        }

        ImageButton translateButon = (ImageButton) findViewById(R.id.TranslateButton);
        if (translateButon!=null)
            translateButon.setEnabled(networkHelper.isOnline());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isNewUser = prefs.getBoolean("newUser",true);
        if (!isNewUser) {
            TextView hintView = (TextView) findViewById(R.id.HintView);
            hintView.setVisibility(View.GONE);
        }
        boolean supportRIL = prefs.getBoolean("ril_enable",false);
        if (supportRIL) {
            Button rilButton = (Button) findViewById(R.id.ril_button);
            if (rilButton!=null) {
                rilButton.setVisibility(View.VISIBLE);

                rilButton.setEnabled(true);
            }
        }

        final Spinner spinner = (Spinner) findViewById(R.id.ot_spinner);
        if (spinner!=null) {

            final UserMentionEntity[] userMentionEntities = status.getUserMentionEntities();
            if (userMentionEntities !=null && userMentionEntities.length>0) {
                spinner.setVisibility(View.VISIBLE);
                List<String> users = new ArrayList<String>(userMentionEntities.length+1);
                // Add an item 0 as dummy, as the spinner auto-selects it
                users.add(getString(R.string.pick_a_user));
                for (int i = 0, userMentionEntitiesLength = userMentionEntities.length;
                     i < userMentionEntitiesLength; i++) {
                    UserMentionEntity ume = userMentionEntities[i];
                    users.add ("@" + ume.getScreenName() + " (" + ume.getName() + ")");
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                        users);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
/*
                spinner.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                        if (i>0) {
                        Intent intent = new Intent(OneTweetActivity.this, UserDetailActivity.class);
                        intent.putExtra("userName", userMentionEntities[i-1].getScreenName());
                        startActivity(intent);
                        }
                    }
                });
*/
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        // Our dummy is at position 0
                        if (position>0) {
                            Intent intent = new Intent(OneTweetActivity.this, UserDetailActivity.class);
                            intent.putExtra("userName", userMentionEntities[position-1].getScreenName());
                            startActivity(intent);
                        }
                        // reset to item 0, so that repeated selection of the same item works.
                        spinner.setSelection(0);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }
            else {
                spinner.setVisibility(View.INVISIBLE);
            }
        }

	}

    @Override
    protected void onResume() {
        super.onResume();

        setupspeak();

    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts!=null) {
            tts.shutdown();
            tts=null;
        }
    }

    private void setTweetText(TextView tweetView) {
        String[] tokens = status.getText().split(" ");
        StringBuilder builder = new StringBuilder();
        for (String token: tokens) {
            if (token.startsWith("http")) {
                boolean found=false;
                if (status.getMediaEntities()!=null) {
                    for (MediaEntity me : status.getMediaEntities()) {
                        if (me.getURL().toString().equals(token)) {
                            builder.append(me.getDisplayURL());
                            found=true;
                            break;
                        }
                     }
                }

                if (!found && status.getURLEntities()!=null) {
                    for (URLEntity ue : status.getURLEntities()) {
                        if (ue.getURL()!=null && ue.getURL().toString().equals(token)) {
                            if (ue.getExpandedURL() != null) {
                                builder.append(ue.getExpandedURL());
                                found = true;
                                break;
                            }
                        }
                    }
                }
                if (!found)
                    builder.append(token);
            }
            else {
                builder.append(token);
            }
            builder.append(" ");
        }
        tweetView.setText(builder.toString());
    }

    /**
     * Display display of the details of a user from pressing
     * the user icon button.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void displayUserDetail(View v) {
        Intent i = new Intent(this, UserDetailActivity.class);
        User theUser;
        if (status.getRetweetedStatus()==null) {
            theUser = status.getUser();
        } else {
            theUser = status.getRetweetedStatus().getUser();
        }
        i.putExtra("userName", theUser.getScreenName());
        i.putExtra("userId", theUser.getId());
        startActivity(i);
    }

    /**
     * Trigger replying to the current status.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void reply(View v) {
		Intent i = new Intent(this, NewTweetActivity.class);
		i.putExtra(getString(R.string.status), status);
		i.putExtra("op", getString(R.string.reply));
		startActivity(i);

	}

    /**
     * Trigger replying to all users mentioned via @xxx in the
     * current status. Opens an editor Window first
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void replyAll(View v) {
		Intent i = new Intent(this, NewTweetActivity.class);
		i.putExtra(getString(R.string.status), status);
		i.putExtra("op", getString(R.string.replyall));
		startActivity(i);

	}

    /**
     * Trigger a resent of the current status
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void retweet(View v) {
        UpdateRequest request = new UpdateRequest(UpdateType.RETWEET);
        request.id = status.getId();
        new UpdateStatusTask(this,pg, account).execute(request);
	}


    /**
     * Do the classical re-send thing by prefixing with 'RT'.
     * Opens an editor window first.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void classicRetweet(View v) {
		Intent i = new Intent(this, NewTweetActivity.class);
		i.putExtra(getString(R.string.status), status);
		i.putExtra("op", getString(R.string.classicretweet));
		startActivity(i);

	}

    /**
     * Starts a view that shows the conversation around the current
     * status.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void threadView(View v) {
        Intent i = new Intent(this,ThreadListActivity.class);
        i.putExtra("startId", status.getId());
        startActivity(i);
    }

    /**
     * Marks the current status as (non) favorite
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void favorite(View v) {
        ImageView favoriteButton = (ImageView) findViewById(R.id.FavoriteButton);

        UpdateRequest request = new UpdateRequest(UpdateType.FAVORITE);
        request.status = status;
        request.view = favoriteButton;

        new UpdateStatusTask(this,pg, account).execute(request);

    }

    /**
     * Start sending a direct message to the user that sent this
     * status.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void directMessage(View v) {
        Intent i = new Intent(this, NewTweetActivity.class);
        i.putExtra(getString(R.string.status), status);
        i.putExtra("op", getString(R.string.direct));
        startActivity(i);

    }

    /**
     * Add the current status to the ReadIt Later list.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void readItLater(View v) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String user = prefs.getString("ril_user","");
        String password = prefs.getString("ril_password","");

        if (user.equals("") || password.equals("")) {
            Toast.makeText(this,getString(R.string.no_ril_user),Toast.LENGTH_LONG).show();
            return;
        }

        String url;
        if (account.isStatusNet())
            url = "https://identi.ca/notice/" + status.getId();
        else
            url = "https://twitter.com/#!/" + status.getUser().getScreenName() + "/status/" + status.getId();

        UpdateRequest request = new UpdateRequest(UpdateType.LATER_READING);
        request.status = status;
        request.url = url;
        request.extUser = user;
        request.extPassword = password;

        new UpdateStatusTask(this,pg, account).execute(request);

    }

    //////////////// speak related stuff ////////////////////

    public void onInit(int status) {
    }

    public void onUtteranceCompleted(String utteranceId) {
        Log.i("speak", "Utterance done: " + utteranceId);

    }

    /**
     * Setup speak just in case we may need it.
     * If directly called from within speak() it will not work
     * because the onInit() listener is no ready early enough
     */
    void setupspeak() {

		tts = new TextToSpeech(this,this);
        tts.setLanguage(Locale.US);
    }

    /**
     * Speak the current status via TTS
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
    public void speak(View v)
    {
        int res = tts.setOnUtteranceCompletedListener(this);
        if (res==TextToSpeech.ERROR) {
            Log.e("1TA", "Failed to set on utterance listener");
        }

        HashMap<String, String> ttsParams = new HashMap<String, String>();
		ttsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tweet_status_msg" + status.getUser().getScreenName());
		tts.speak(status.getText(), TextToSpeech.QUEUE_FLUSH, ttsParams);

	}


    //////////////// speak related stuff end ////////////////////

    /**
     * Send a message via Email or ...
     * Invoked from the send button
     * @param v view this is invoked on
     */
    @SuppressWarnings("unused")
    public void send(View v) {
        User user = status.getUser();
        String s = getString(R.string.wrote);
        String wrote = user.getName() + "(@" + user.getScreenName() + ") " + s + ":";
        String text = wrote + "\n\n" + status.getText();
        String url ;
        if (account.getServerType().equals("twitter")) {
            url = "http://twitter.com/#!/" + user.getScreenName() + "/status/" + status.getId();
            text = text + "\n\n" + url;
        }

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,text);
        String subject = getString(R.string.message_from_zwitscher);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);

        startActivity(i);

    }

    /**
     * Translate the current status by calling Google translate.
     * Target language is the users current locale.
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void translate(View v) {
		Translate.setHttpReferrer("http://bsd.de/zwitscher");
//		try {
//            targetLanguage = Language.fromString(locale);
//			String result = Translate.execute(status.getText(), Language.AUTO_DETECT, targetLanguage);
//		} catch (Exception e) {
//			e.printStackTrace();
//			Toast.makeText(getApplicationContext(), e.getMessage(), 15000).show();
//		}
        String locale = Locale.getDefault().getLanguage();
        new TranslateTask(this).execute(status.getText(),locale);
	}

    private static class TranslateTask extends AsyncTask<String,Void,String> {

        private Context context;
        ProgressDialog dialog;

        private TranslateTask(Context context) {
            this.context = context;
        }

        protected String doInBackground(String... strings) {
            String text = strings[0];
            String locale = strings[1];
            Language targetLanguage = Language.fromString(locale);
            try {
                return Translate.execute(text, Language.AUTO_DETECT, targetLanguage);
            } catch (Exception e) {
                e.printStackTrace();
                return e.getLocalizedMessage();
            }
        }

        protected void onPreExecute() {
            dialog = new ProgressDialog(context);
            dialog.setTitle(context.getString(R.string.translating));
            dialog.setCancelable(false);
            dialog.show();
        }

        protected void onPostExecute(String result) {
            dialog.cancel();
            dialog.hide();

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage(result);
            builder.setTitle(context.getString(R.string.translation_result));
            builder.setNeutralButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            AlertDialog alert = builder.create();
            alert.show();
        }
    }

    /**
     * Finishes this screen and returns to the list of statuses
     * @param v View that has been clicked
     */
    @SuppressWarnings("unused")
	public void done(View v) {
        if (tts!=null)
            tts.shutdown();
		finish();
	}


    /**
     * Return urls for thumbnails of linked images if the url in the status is recognized
     * as an image service
     * @param status Status to analyze
     * @return List of Bitmaps to display
     */
    private List<UrlPair> parseForPictureUrls(Status status) {
        Set<String> urls = new HashSet<String>();
        ArrayList<UrlPair> urlPairs = new ArrayList<UrlPair>();


        MediaEntity[] mediaEntities = status.getMediaEntities();
        if (mediaEntities!=null) {
            for (MediaEntity me : mediaEntities) {
                URL url = me.getURL();
                String target;
                target = me.getMediaURL().toString();
                if (android.os.Build.VERSION.SDK_INT<11) // TODO decide on screen size and not API version
                    target = target+ ":thumb";
                else
                    target = target+ ":small";
                UrlPair pair = new UrlPair(url.toString(), target);
                urlPairs.add(pair);
            }
        }
        if (urlPairs.size()>0)
            return urlPairs; // Urls provided by twitter, so we're done.

        URLEntity[] ures = status.getURLEntities();
        if (ures!=null) {
            for (URLEntity ue : ures) {
                URL url = ue.getExpandedURL();
                if (url!=null)
                    urls.add(url.toString());
            }
        }


        // Nothing provided by twitter, so parse the text
        if (urls.size()==0 && urlPairs.size()==0) {
            String[] tokens = status.getText().split(" ");
            for (String token : tokens) {
                if (token.startsWith("http://") || token.startsWith("https://")) {
                    urls.add(token);
                }
            }
        }
        if (urls.size()==0)
            return urlPairs;




        // We have urls, so check for picture services
        for (String url :  urls) {
            Log.d("One tweet","Url = " + url);
//            url = UrlHelper.expandUrl(url); // expand link shorteners TODO that ultimately needs to go into the main parsing for all kinds of links
            String finalUrlString="";
            if (url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".jpeg")) {
                finalUrlString = url;
            }
            else if (url.contains("yfrog.com")) {
                finalUrlString = url + ".th.jpg";
            }
            else if (url.contains("twitpic.com")) {
                String tmp = url;
                tmp = tmp.substring(tmp.lastIndexOf("/")+1);
                finalUrlString = "http://twitpic.com/show/thumb/" + tmp;
            }
            else if (url.contains("plixi.com")) {
                finalUrlString = "http://api.plixi.com/api/tpapi.svc/imagefromurl?size=thumbnail&url=" +  url;
            }
            else if (url.contains("twimg")) { // This is the normal twitter picture entity
                finalUrlString = url;
            }
            else if (url.contains("i.imgur.com")) {
                String tmp = url.substring(0,url.lastIndexOf('.'));
                finalUrlString = tmp + "s" + url.substring(url.lastIndexOf('.'));
            }
            else if (url.contains("://instagr.am/p/")) {
                finalUrlString = url + "media";  //   /?size= { t, m ,l } default is m
            }
            else {
                String screenName;
                long statusId;
                if (!status.isRetweet()) {
                    screenName = status.getUser().getScreenName();
                    statusId = status.getId();
                }
                else {
                    screenName = status.getRetweetedStatus().getUser().getScreenName();
                    statusId = status.getRetweetedStatus().getId();
                }
                String twitterPic = "http://twitter.com/" + screenName + "/status/" + statusId +
                        "/photo";
                if (url.startsWith(twitterPic)) {
                    // TODO forward to
                    // "http://twitter.com/#!" + status.getUser().getScreenName() + "/status/" + status.getId()
                    // and then grab the image url from there
//                    finalUrlString = UrlHelper.grabPictureUrlFromTwitter(url);
                    finalUrlString = url; //TODO
                }
                else {
                    Log.d("OTA::loadThumbnails", "Url " + url + " not supported for preview");
                    continue;
                }
            }

            UrlPair pair = new UrlPair(url,finalUrlString);
            urlPairs.add(pair);
        }
        return urlPairs;
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        if (android.os.Build.VERSION.SDK_INT>=11) {
            inflater.inflate(R.menu.one_tweet_menu_honey,menu);

            ActionBar actionBar = this.getActionBar();
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        else {
            inflater.inflate(R.menu.one_tweet_menu,menu);

        }
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.reply:
                reply(null);
                break;
            case R.id.retweet:
                retweet(null);
                break;
            case R.id.direct:
                directMessage(null);
                break;
            case R.id.thread:
                threadView(null);
                break;
            case R.id.replyAll:
                replyAll(null);
                break;
            case R.id.cl_retweet:
                classicRetweet(null);
                break;
            case R.id.favorite:
                favorite(null);
                break;
            case R.id.translate:
                translate(null);
                break;
            case R.id.forward:
                send(null);
                break;
            case R.id.speak:
                speak(null);
                break;
            case R.id.ril:
                readItLater(null);
                break;
            case R.id.report_as_spam:
                report_spam();
                break;

            default:
                Log.e(getClass().getName(),"Unknown menu item: " + item.toString());
        }

        return super.onOptionsItemSelected(item);

    }

    void report_spam() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setTitle(R.string.report_as_spammer);
        builder.setCancelable(false);
        String msg = getString(R.string.report_as_spammer_really,status.getUser().getScreenName());
        builder.setMessage(msg);

        builder.setPositiveButton(R.string.yes,new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                UpdateRequest request = new UpdateRequest(UpdateType.REPORT_AS_SPAMMER);
                request.id = status.getUser().getId();

                new UpdateStatusTask(OneTweetActivity.this,pg, account).execute(request);
            }
        });
        builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.cancel();
            }
        });
        builder.create().show();

    }

    /**
     * Load thumbnails of linked images for the passed listOfUrlPairs
     * @param listOfUrlPairs list of images to load (thumbnail url, url to full img)
     * @return List of bitmaps along
     */
    private List<BitmapWithUrl> loadThumbnails(List<UrlPair> listOfUrlPairs) {

        List<BitmapWithUrl> bitmaps = new ArrayList<BitmapWithUrl>(listOfUrlPairs.size());

        for (UrlPair urlPair : listOfUrlPairs) {
            Log.i("loadThumbnail", "URL to load is " + urlPair.getTarget());

            try {
                URL picUrl = new URL(urlPair.getTarget());
                BufferedInputStream in = new BufferedInputStream(picUrl.openStream());
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                in.close();
                BitmapWithUrl bwu = new BitmapWithUrl(bitmap,urlPair.getSrc());
                bitmaps.add(bwu);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        return bitmaps;
    }



    /**
     * Background task to download the user profile images.
     */
    private class DownloadUserImageTask extends AsyncTask<User, Void,Bitmap> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (pg!=null)
                pg.setVisibility(ProgressBar.VISIBLE);
        }

        protected Bitmap doInBackground(User... users) {

            User user = users[0];
            PicHelper picHelper = new PicHelper();
            Bitmap bi;
            if (downloadPictures)
                bi = picHelper.fetchUserPic(user);
            else
                bi = picHelper.getBitMapForUserFromFile(user);
            return bi;
        }


        protected void onPostExecute(Bitmap result) {
        	if (result!=null)
        		userPictureView.setImageBitmap(result);
            if (pg!=null)
                pg.setVisibility(ProgressBar.INVISIBLE);
        }
    }

    /**
     * Background task to download the thumbnails of linked images
     */
    private class DownloadImagePreviewsTask extends AsyncTask<Void,Void,List<BitmapWithUrl>> {

        private Context context;
        private twitter4j.Status status;

        private DownloadImagePreviewsTask(Context context, twitter4j.Status status) {
            this.context = context;
            this.status = status;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (pg!=null)
                pg.setVisibility(ProgressBar.VISIBLE);
            if (titleTextView!=null) {
                String text = getString(R.string.get_preview);
                titleTextView.setText(text);
            }
        }

        @Override
        protected List<BitmapWithUrl> doInBackground(Void... params) {
            List<UrlPair> pictureUrls;
            if (status.isRetweet())
                pictureUrls = parseForPictureUrls(status.getRetweetedStatus());
            else
                pictureUrls = parseForPictureUrls(status);
            List<BitmapWithUrl> bitmapList=null;
            if (downloadPictures)
                bitmapList = loadThumbnails(pictureUrls);
            return bitmapList;
        }

        @Override
        protected void onPostExecute(final List<BitmapWithUrl> bitmaps) {
            super.onPostExecute(bitmaps);
            if (bitmaps!=null) {
                Gallery g = (Gallery) findViewById(R.id.gallery);
                ImageAdapter adapter = new ImageAdapter(context);

                adapter.addImages(bitmaps);
                g.setAdapter(adapter);
                g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        String url = bitmaps.get(position).url;
                        Uri uri = Uri.parse(url);
                        Intent i = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(i);
                    }
                });
            }

            if (titleTextView!=null)
                titleTextView.setText("");
            if (pg!=null)
            pg.setVisibility(ProgressBar.INVISIBLE);
        }
    }

    /**
     * Picture adapter for the Gallery that holds the images
     * It is filled via data obtained via #loadThumbnails
     */
    private class ImageAdapter extends BaseAdapter {

        private List<BitmapWithUrl> mImages = new ArrayList<BitmapWithUrl>();

        void addImages(List<BitmapWithUrl> images) {
            mImages.addAll(images);
        }

        int mGalleryItemBackground;

        public ImageAdapter(Context c) {
            mContext = c;
            // See res/values/style.xml for the <declare-styleable> that defines
            // Gallery1.
            TypedArray a = obtainStyledAttributes(R.styleable.Gallery1);
            mGalleryItemBackground = a.getResourceId(
                    R.styleable.Gallery1_android_galleryItemBackground, 0);
            a.recycle();
        }

        public int getCount() {
            return mImages.size();
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView i = new ImageView(mContext);

            Bitmap bitmap = mImages.get(position).bitmap;
            if (bitmap!=null) {
                i.setImageBitmap(bitmap);
                i.setScaleType(ImageView.ScaleType.FIT_XY);
                i.setLayoutParams(new Gallery.LayoutParams(bitmap.getWidth()*2, bitmap.getHeight()*2));
            }
            // The preferred Gallery item background
            i.setBackgroundResource(mGalleryItemBackground);

            return i;
        }

        private Context mContext;
    }

    /**
     * Helper class that holds a bitmap along with the picture url,
     * so that a click on the image in the gallery can start a browser
     * window to the image service to browser the full size one.
     */
    private static class BitmapWithUrl {
        Bitmap bitmap;
        String url;

        public BitmapWithUrl(Bitmap bitmap, String picUrlString) {
            this.bitmap = bitmap;
            this.url = picUrlString;
        }
    }

}
