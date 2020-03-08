package emasterson.iot_printing;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.AWSStartupHandler;
import com.amazonaws.mobile.client.AWSStartupResult;
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.iot.AWSIotClient;
import com.amazonaws.services.iot.model.AttachPrincipalPolicyRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateResult;
import com.amazonaws.services.s3.AmazonS3Client;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.util.UUID;

// S3 code reference: https://docs.aws.amazon.com/aws-mobile/latest/developerguide/add-aws-mobile-user-data-storage.html
// File picker code reference: https://developer.android.com/guide/topics/providers/document-provider
public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener, View.OnClickListener{
    EditText txtSubscribe;
    TextView tvLastMessage, tvClientId, tvStatus, tvFileName;
    ToggleButton btnConnect, btnSubscribe;
    Button btnChoose, btnPrint;
    String filePath, fileName;
    File file;

    static final String LOG_TAG = MainActivity.class.getCanonicalName();
    // --- Constants to modify per your configuration ---

    // IoT endpoint
    // AWS Iot CLI describe-endpoint call returns: XXXXXXXXXX.iot.<region>.amazonaws.com
    private static final String CUSTOMER_SPECIFIC_ENDPOINT = "";
    // Cognito pool ID. For this app, pool needs to be unauthenticated pool with
    // AWS IoT permissions.
    private static final String COGNITO_POOL_ID = "";
    // Name of the AWS IoT policy to attach to a newly created certificate
    private static final String AWS_IOT_POLICY_NAME = "iotprint_policy";
    // Region of AWS IoT
    private static final Regions MY_REGION = Regions.EU_WEST_1;
    // Filename of KeyStore file on the filesystem
    private static final String KEYSTORE_NAME = "iot_keystore";
    // Password for the private key in the KeyStore
    private static final String KEYSTORE_PASSWORD = "password";
    // Certificate and key aliases in the KeyStore
    private static final String CERTIFICATE_ID = "default";

    private static final String PI_TOPIC = "iotprint/message";

    private static final String KEY = "";
    private static final String SECRET = "";

    private static final int CHOOSING_REQUEST = 1;

    AmazonS3Client s3Client;
    BasicAWSCredentials credentials;
    AWSIotClient mIotAndroidClient;
    AWSIotMqttManager mqttManager;
    String clientId;
    String keystorePath;
    String keystoreName;
    String keystorePassword;

    KeyStore clientKeyStore = null;
    String certificateId;

    CognitoCachingCredentialsProvider credentialsProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtSubscribe = findViewById(R.id.txtSubscribe);
        tvLastMessage = findViewById(R.id.tvLastMessage);
        tvClientId = findViewById(R.id.tvClientId);
        tvStatus = findViewById(R.id.tvStatus);
        tvFileName = findViewById(R.id.tvFileName);

        btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnCheckedChangeListener(this);
        btnConnect.setEnabled(false);

        btnSubscribe = findViewById(R.id.btnSubscribe);
        btnSubscribe.setOnCheckedChangeListener(this);

        btnChoose = findViewById(R.id.btnChoose);
        btnChoose.setOnClickListener(this);

        btnPrint = findViewById(R.id.btnPrint);
        btnPrint.setOnClickListener(this);
        btnPrint.setEnabled(false);

        AWSMobileClient.getInstance().initialize(this).execute();
        credentials = new BasicAWSCredentials(KEY, SECRET);
        s3Client = new AmazonS3Client(credentials);

        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        clientId = UUID.randomUUID().toString();
        tvClientId.setText(clientId);

        // Initialize the AWS Cognito credentials provider
        credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(), // context
                COGNITO_POOL_ID, // Identity Pool ID
                MY_REGION // Region
        );

        Region region = Region.getRegion(MY_REGION);
        // MQTT Client
        mqttManager = new AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT);

        // Set keepalive to 10 seconds.  Will recognize disconnects more quickly but will also send
        // MQTT pings every 10 seconds.
        mqttManager.setKeepAlive(10);

        // Set Last Will and Testament for MQTT.  On an unclean disconnect (loss of connection)
        // AWS IoT will publish this message to alert other clients.
        AWSIotMqttLastWillAndTestament lwt = new AWSIotMqttLastWillAndTestament("my/lwt/topic", "Android client lost connection", AWSIotMqttQos.QOS0);
        mqttManager.setMqttLastWillAndTestament(lwt);

        // IoT Client (for creation of certificate if needed)
        mIotAndroidClient = new AWSIotClient(credentialsProvider);
        mIotAndroidClient.setRegion(region);

        keystorePath = getFilesDir().getPath();
        keystoreName = KEYSTORE_NAME;
        keystorePassword = KEYSTORE_PASSWORD;
        certificateId = CERTIFICATE_ID;

        // To load cert/key from keystore on filesystem
        try {
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                if (AWSIotKeystoreHelper.keystoreContainsAlias(certificateId, keystorePath, keystoreName, keystorePassword)) {
                    Log.i(LOG_TAG, "Certificate " + certificateId + " found in keystore - using for MQTT.");
                    // load keystore from file into memory to pass on connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId, keystorePath, keystoreName, keystorePassword);
                    btnConnect.setEnabled(true);
                } else {
                    Log.i(LOG_TAG, "Key/cert " + certificateId + " not found in keystore.");
                }
            } else {
                Log.i(LOG_TAG, "Keystore " + keystorePath + "/" + keystoreName + " not found.");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "An error occurred retrieving cert/key from keystore.", e);
        }

        if (clientKeyStore == null) {
            Log.i(LOG_TAG, "Cert/key was not found in keystore - creating new key and certificate.");

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Create a new private key and certificate. This call
                        // creates both on the server and returns them to the
                        // device.
                        CreateKeysAndCertificateRequest createKeysAndCertificateRequest = new CreateKeysAndCertificateRequest();
                        createKeysAndCertificateRequest.setSetAsActive(true);
                        final CreateKeysAndCertificateResult createKeysAndCertificateResult;
                        createKeysAndCertificateResult = mIotAndroidClient.createKeysAndCertificate(createKeysAndCertificateRequest);
                        Log.i(LOG_TAG, "Cert ID: " + createKeysAndCertificateResult.getCertificateId() + " created.");

                        // store in keystore for use in MQTT client
                        // saved as alias "default" so a new certificate isn't
                        // generated each run of this application
                        AWSIotKeystoreHelper.saveCertificateAndPrivateKey(certificateId,
                                createKeysAndCertificateResult.getCertificatePem(),
                                createKeysAndCertificateResult.getKeyPair().getPrivateKey(),
                                keystorePath, keystoreName, keystorePassword);

                        // load keystore from file into memory to pass on
                        // connection
                        clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId, keystorePath, keystoreName, keystorePassword);

                        // Attach a policy to the newly created certificate.
                        // This flow assumes the policy was already created in
                        // AWS IoT and we are now just attaching it to the
                        // certificate.
                        AttachPrincipalPolicyRequest policyAttachRequest = new AttachPrincipalPolicyRequest();
                        policyAttachRequest.setPolicyName(AWS_IOT_POLICY_NAME);
                        policyAttachRequest.setPrincipal(createKeysAndCertificateResult.getCertificateArn());
                        mIotAndroidClient.attachPrincipalPolicy(policyAttachRequest);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnConnect.setEnabled(true);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Exception occurred when generating new private key and certificate.", e);
                    }
                }
            }).start();
        }
    }

    // Send message to topic on button press
    public void sendMessage(String fileLink, String fileName){
        JSONObject json = new JSONObject();
        try{
            json.put("id", UUID.randomUUID().toString());
            json.put("fileLink", fileLink);
            json.put("filename", fileName);
            json.put("print", "true");
        } catch (JSONException e){
            e.printStackTrace();
        }

        try {
            mqttManager.publishString(json.toString(), PI_TOPIC, AWSIotMqttQos.QOS0);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Publish error.", e);
        }
    }

    // On successful connection, we subscribe to all topics to retrieve sensor readings
    public void subscribe(String topic){
        try {
            mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        Log.d(LOG_TAG, "Message arrived:");
                                        Log.d(LOG_TAG, "   Topic: " + topic);
                                        Log.d(LOG_TAG, " Message: " + message);
                                        tvLastMessage.setText(message);
                                    } catch (UnsupportedEncodingException e) {
                                        Log.e(LOG_TAG, "Message encoding error.", e);
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(LOG_TAG, "Subscription error.", e);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        JSONObject message = new JSONObject();
        switch(buttonView.getId()){
            case R.id.btnConnect:
                if(isChecked){
                    Log.d(LOG_TAG, "clientId = " + clientId);

                    try {
                        mqttManager.connect(clientKeyStore, new AWSIotMqttClientStatusCallback() {
                            @Override
                            public void onStatusChanged(final AWSIotMqttClientStatus status,
                                                        final Throwable throwable) {
                                Log.d(LOG_TAG, "Status = " + String.valueOf(status));

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (status == AWSIotMqttClientStatus.Connecting) {
                                            tvStatus.setText("Connecting...");

                                        } else if (status == AWSIotMqttClientStatus.Connected) {
                                            tvStatus.setText("Connected");
                                        } else if (status == AWSIotMqttClientStatus.Reconnecting) {
                                            if (throwable != null) {
                                                Log.e(LOG_TAG, "Connection error.", throwable);
                                            }
                                            tvStatus.setText("Reconnecting");
                                        } else if (status == AWSIotMqttClientStatus.ConnectionLost) {
                                            if (throwable != null) {
                                                Log.e(LOG_TAG, "Connection error.", throwable);
                                            }
                                            tvStatus.setText("Disconnected");
                                        } else {
                                            tvStatus.setText("Disconnected");

                                        }
                                    }
                                });
                            }
                        });
                    } catch (final Exception e) {
                        Log.e(LOG_TAG, "Connection error.", e);
                        tvStatus.setText("Error! " + e.getMessage());
                    }
                    break;
                } else {
                    try {
                        mqttManager.disconnect();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Disconnect error.", e);
                    }
                    break;
                }
            case R.id.btnSubscribe:
                final String topic = txtSubscribe.getText().toString();
                if(!tvStatus.getText().equals("Connected")) {
                    Toast.makeText(getApplicationContext(), "Connect to Subscribe", Toast.LENGTH_LONG).show();
                    btnSubscribe.setChecked(false);
                    break;
                }
                else if(isChecked){
                    Log.d(LOG_TAG, "topic = " + topic);

                    try {
                        if(topic == null){
                            Toast.makeText(getApplicationContext(), "Enter Topic to Subscribe", Toast.LENGTH_LONG).show();
                            break;
                        }
                        mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0,
                                new AWSIotMqttNewMessageCallback() {
                                    @Override
                                    public void onMessageArrived(final String topic, final byte[] data) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    String message = new String(data, "UTF-8");
                                                    Log.d(LOG_TAG, "Message arrived:");
                                                    Log.d(LOG_TAG, "   Topic: " + topic);
                                                    Log.d(LOG_TAG, " Message: " + message);

                                                    tvLastMessage.setText(message);

                                                } catch (UnsupportedEncodingException e) {
                                                    Log.e(LOG_TAG, "Message encoding error.", e);
                                                }
                                            }
                                        });
                                    }
                                });
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Subscription error.", e);
                    }
                    break;
                } else {
                    try {
                        mqttManager.unsubscribeTopic(topic);
                        tvLastMessage.setText("Unsubscribed");
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Disconnect error.", e);
                    }
                    break;
                }
        }
    }

    @Override
    public void onClick(View v) {
        if(v.getId() == R.id.btnChoose){
            chooseFile();
        } else if(v.getId() == R.id.btnPrint){
            uploadFile();
            if (!tvStatus.getText().equals("Connected")) {
                Toast.makeText(this,"Unable to print file, make sure your connected.", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void chooseFile(){
        Intent intent = new Intent();
//        intent.setAction(Intent.ACTION_VIEW);
        intent.setAction(Intent.ACTION_GET_CONTENT);
//        intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.setType("*/*");
        startActivityForResult(intent, CHOOSING_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (resultCode == RESULT_OK) {
            Uri uri = resultData.getData();
            try {
                filePath = getPath(uri);
                file = new File(filePath);
                fileName = file.getName();
                tvFileName.setText(fileName);
                btnPrint.setEnabled(true);
            } catch (URISyntaxException e) {
                Toast.makeText(this,"Unable to get the file from the given URI.  See error log for details", Toast.LENGTH_LONG).show();
                Log.e(LOG_TAG, "Unable to upload file from the given uri", e);
            }
        }
    }

    public void uploadFile(){
        AWSMobileClient.getInstance().initialize(this, new AWSStartupHandler() {
            @Override
            public void onComplete(AWSStartupResult awsStartupResult) {
                uploadWithTransferUtility();
            }
        }).execute();
    }

    public void uploadWithTransferUtility(){
        if (filePath == null) {
            Toast.makeText(this, "Could not find the filepath of the selected file", Toast.LENGTH_LONG).show();
            return;
        }
        TransferUtility transferUtility =
                TransferUtility.builder()
                        .context(getApplicationContext())
                        .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
                        .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
                        .build();
        TransferObserver uploadObserver = transferUtility.upload(fileName, file);

        // Attach a listener to the observer to get state update and progress notifications
        uploadObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                if (TransferState.COMPLETED == state) {
                    // Handle a completed upload.
                    String fileLink = "https://s3-eu-west-1.amazonaws.com/iotprint-userfiles-mobilehub-1101451694/uploads/"+fileName;
                    sendMessage(fileLink, fileName);
                    subscribe("iotprint/response");
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                float percentDonef = ((float) bytesCurrent / (float) bytesTotal) * 100;
                int percentDone = (int)percentDonef;
                Log.d(LOG_TAG, "ID:" + id + " bytesCurrent: " + bytesCurrent + " bytesTotal: " + bytesTotal + " " + percentDone + "%");
            }

            @Override
            public void onError(int id, Exception ex) {
                // Handle errors
            }

        });
    }

    /*
     * Gets the file path of the given Uri.
     */
    @SuppressLint("NewApi")
    private String getPath(Uri uri) throws URISyntaxException {
//        final boolean needToCheckUri = Build.VERSION.SDK_INT >= 19;
        String selection = null;
        String[] selectionArgs = null;
        // Uri is different in versions after KITKAT (Android 4.4), we need to
        // deal with different Uris.
        if (DocumentsContract.isDocumentUri(getApplicationContext(), uri)) {
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                return Environment.getExternalStorageDirectory() + "/" + split[1];
            } else if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                uri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
            } else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("image".equals(type)) {
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                selection = "_id=?";
                selectionArgs = new String[] {
                        split[1]
                };
            }
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            String[] projection = {
                    MediaStore.Images.Media.DATA
            };
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null);
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                if (cursor.moveToFirst()) {
                    return cursor.getString(column_index);
                }
            } catch (Exception e) {
            }
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }
}
