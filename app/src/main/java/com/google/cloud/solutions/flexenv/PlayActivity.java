/**
 # Copyright 2016 Google LLC.
 # Licensed under the Apache License, Version 2.0 (the "License");
 # you may not use this file except in compliance with the License.
 # You may obtain a copy of the License at
 #
 # http://www.apache.org/licenses/LICENSE-2.0
 #
 # Unless required by applicable law or agreed to in writing, software
 # distributed under the License is distributed on an "AS IS" BASIS,
 # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 # See the License for the specific language governing permissions and
 # limitations under the License.
 **/

package com.google.cloud.solutions.flexenv;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.cloud.solutions.flexenv.common.Message;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 * Main activity to select a channel and exchange messages with other users
 * The app expects users to authenticate with Google ID. It also sends user
 * activity logs to a Servlet instance through Firebase.
 */
public class PlayActivity
        extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        GoogleApiClient.OnConnectionFailedListener,
        View.OnKeyListener,
        View.OnClickListener {

    // Firebase keys commonly used with backend Servlet instances
    private static final String IBX = "inbox";
    private static final String CHS = "channels";
    private static final String REQLOG = "requestLogger";

    private static final int RC_SIGN_IN = 9001;

    private static final String TAG = "PlayActivity";
    private static FirebaseLogger fbLog;

    private GoogleApiClient mGoogleApiClient;
    private GoogleSignInAccount acct;
    private DatabaseReference databaseReference;
    private FirebaseAuth auth;
    private FirebaseAuth.AuthStateListener authListener;
    private String inbox;
    private String currentChannel;
    private ChildEventListener channelListener;
    private SimpleDateFormat fmt;

    private Menu channelMenu;
    private TextView channelLabel;
    private List<Map<String, String>> messages;
    private SimpleAdapter messageAdapter;
    private EditText messageText;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ListView messageHistory;
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_play);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        channelMenu = navigationView.getMenu();
        navigationView.setNavigationItemSelectedListener(this);
        initChannels(getResources().getString(R.string.channels));

        GoogleSignInOptions gso =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .enableAutoManage(this, this)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .build();
        auth = FirebaseAuth.getInstance();
        authListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    inbox = "client-" + Integer.toString(Math.abs(user.getUid().hashCode()));
                    Log.d(TAG, "onAuthStateChanged:signed_in:" + inbox);
                    status.setText(String.format(getResources().getString(R.string.signed_in_label),
                            user.getDisplayName())
                    );
                    updateUI(true);

                    requestLogger();

                } else {
                    Log.d(TAG, "onAuthStateChanged:signed_out");
                    updateUI(false);
                }
            }
        };

        SignInButton signInButton = findViewById(R.id.sign_in_button);
        signInButton.setSize(SignInButton.SIZE_STANDARD);
        signInButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
                // Start authenticating with Google ID first.
                startActivityForResult(signInIntent, RC_SIGN_IN);
            }
        });
        channelLabel = findViewById(R.id.channelLabel);
        Button signOutButton = findViewById(R.id.sign_out_button);
        signOutButton.setOnClickListener(this);

        messages = new ArrayList<>();
        messageAdapter = new SimpleAdapter(this, messages, android.R.layout.simple_list_item_2,
                new String[]{"message", "meta"}, new int[]{android.R.id.text1, android.R.id.text2});
        messageHistory = findViewById(R.id.messageHistory);
        messageHistory.setAdapter(messageAdapter);
        messageText = findViewById(R.id.messageText);
        messageText.setOnKeyListener(this);
        fmt = new SimpleDateFormat("yy.MM.dd HH:mm z", Locale.US);

        status = findViewById(R.id.status);
    }

    @Override
    public void onStart() {
        super.onStart();
        auth.addAuthStateListener(authListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (authListener != null) {
            auth.removeAuthStateListener(authListener);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
            Log.d(TAG, "SignInResult : " + result.isSuccess());
            // If Google ID authentication is succeeded, obtain a token for Firebase authentication.
            if (result.isSuccess()) {
                acct = result.getSignInAccount();
                status.setText(getResources().getString(R.string.authenticating_label));
                AuthCredential credential = GoogleAuthProvider.getCredential(acct.getIdToken(), null);
                auth.signInWithCredential(credential)
                        .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                            @Override
                            public void onComplete(@NonNull Task<AuthResult> task) {
                                Log.d(TAG, "signInWithCredential:onComplete:" + task.isSuccessful());
                                if (!task.isSuccessful()) {
                                    Log.w(TAG, "signInWithCredential", task.getException());
                                    status.setText(String.format(
                                            getResources().getString(R.string.authentication_failed),
                                            task.getException())
                                    );
                                }
                                else {
                                    fbLog.log(inbox, "Signed in");
                                    updateUI(true);
                                }
                            }
                        });
            } else {
                updateUI(false);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.sign_out_button) {
            signOut();
        }
    }

    private void signOut() {
        Auth.GoogleSignInApi.signOut(mGoogleApiClient).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull Status status) {
                        databaseReference.removeEventListener(channelListener);
                        databaseReference.onDisconnect();
                        inbox = null;
                        acct = null;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateUI(false);
                            }
                        });
                        fbLog.log(inbox, "Signed out");
                    }
                });
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
            databaseReference.child(CHS + "/" + currentChannel)
                    .push()
                    .setValue(new Message(messageText.getText().toString(), acct.getDisplayName()));
            return true;
        }
        return false;
    }

    private void addMessage(String msgString, String meta) {
        Map<String, String> message = new HashMap<>();
        message.put("message", msgString);
        message.put("meta", meta);
        messages.add(message);

        messageAdapter.notifyDataSetChanged();
        messageText.setText("");
    }

    private void updateUI(boolean signedIn) {
        if (signedIn) {
            findViewById(R.id.sign_in_button).setVisibility(View.GONE);
            findViewById(R.id.sign_out_button).setVisibility(View.VISIBLE);
            findViewById(R.id.channelLabel).setVisibility(View.VISIBLE);
            findViewById(R.id.messageText).setVisibility(View.VISIBLE);
            findViewById(R.id.messageHistory).setVisibility(View.VISIBLE);
        }
        else {
            findViewById(R.id.sign_in_button).setVisibility(View.VISIBLE);
            findViewById(R.id.sign_out_button).setVisibility(View.GONE);
            findViewById(R.id.channelLabel).setVisibility(View.INVISIBLE);
            findViewById(R.id.messageText).setVisibility(View.INVISIBLE);
            findViewById(R.id.messageHistory).setVisibility(View.INVISIBLE);
            ((TextView)findViewById(R.id.status)).setText("");
        }
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        messages.clear();

        String msg = "Switching channel to '" + item.toString() + "'";
        try {
            fbLog.log(inbox, msg);
        } catch(NullPointerException e) {
            updateUI(false);
            return false;
        }

        // Switching a listener to the selected channel.
        databaseReference.child(CHS + "/" + currentChannel).removeEventListener(channelListener);
        currentChannel = item.toString();
        databaseReference.child(CHS + "/" + currentChannel).addChildEventListener(channelListener);

        channelLabel.setText(currentChannel);

        return true;
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {}

// [START requestLogger]
    /*
     * Request that a Servlet instance be assigned.
     */
    private void requestLogger() {
        databaseReference = FirebaseDatabase.getInstance().getReference();
        databaseReference.child(IBX + "/" + inbox).removeValue();
        databaseReference.child(IBX + "/" + inbox).addValueEventListener(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists() && snapshot.getValue(String.class) != null) {
                    fbLog = new FirebaseLogger(
                            databaseReference,
                            IBX + "/" + snapshot.getValue(String.class) + "/logs"
                    );
                    databaseReference.child(IBX + "/" + inbox).removeEventListener(this);
                }
            }

            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, error.getDetails());
            }
        });

        databaseReference.child(REQLOG).push().setValue(inbox);
    }
// [END requestLogger]

    /*
     * Initialize pre-defined channels as Activity menu.
     * Once a channel is selected, ChildEventListener is attached and
     * waits for messages.
     */
    private void initChannels(String channelString) {
        Log.d(TAG, "Channels : " + channelString);
        String[] topicArr = channelString.split(",");
        for (String topic : topicArr) {
            channelMenu.add(topic);
        }

        channelListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String prevKey) {
                Message message = snapshot.getValue(Message.class);
                // Extract attributes from Message object to display on the screen.
                if(message != null && !message.getText().isEmpty()) {
                    addMessage(message.getText(), fmt.format(new Date(message.getTimeLong())) + " "
                            + message.getDisplayName());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, error.getDetails());
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String prevKey) {}

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {}

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String prevKey) {}
        };
    }
}
