/*
 * Copyright 2018 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.sceneform.samples.hellosceneform;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;
import java.io.File;
import java.io.IOException;
import org.jcodec.api.android.AndroidSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;

/**
 * This is an example activity that uses the Sceneform UX package to make common AR tasks easier.
 */
public class HelloSceneformActivity extends AppCompatActivity implements Scene.OnUpdateListener {
  private static final String TAG = HelloSceneformActivity.class.getSimpleName();
  private static final double MIN_OPENGL_VERSION = 3.0;

  private ArFragment arFragment;
  private ModelRenderable andyRenderable;

  private boolean isRecording;
  private HandlerThread videoProcessingThread;
  private Handler videoProcessingHandler;

  private int messages = 60; //How many messages/bitmaps we want to encode

  @Override @SuppressWarnings({ "AndroidApiChecker", "FutureReturnValueIgnored" })
  // CompletableFuture requires api level 24
  // FutureReturnValueIgnored is not valid
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!checkIsSupportedDeviceOrFinish(this)) {
      return;
    }

    setContentView(R.layout.activity_ux);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);
    arFragment.getArSceneView().getScene().addOnUpdateListener(this);

    // When you build a Renderable, Sceneform loads its resources in the background while returning
    // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
    ModelRenderable.builder()
        .setSource(this, R.raw.andy)
        .build()
        .thenAccept(renderable -> andyRenderable = renderable)
        .exceptionally(throwable -> {
          Toast toast = Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG);
          toast.setGravity(Gravity.CENTER, 0, 0);
          toast.show();
          return null;
        });

    arFragment.setOnTapArPlaneListener(
        (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
          if (andyRenderable == null) {
            return;
          }

          // Create the Anchor.
          Anchor anchor = hitResult.createAnchor();
          AnchorNode anchorNode = new AnchorNode(anchor);
          anchorNode.setParent(arFragment.getArSceneView().getScene());

          // Create the transformable andy and add it to the anchor.
          TransformableNode andy = new TransformableNode(arFragment.getTransformationSystem());
          andy.setParent(anchorNode);
          andy.setRenderable(andyRenderable);
          andy.select();
        });

    Button recordButton = findViewById(R.id.screen_record_button);
    recordButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (!isRecording) {

          File outputFile = new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                  + "/jcodec",
              "jcodec-record-" + Long.toHexString(System.currentTimeMillis()) + ".mp4");
          if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
          }

          //If it is not recording yet, we will switch to recording so prepare the bg thread
          //Create background thread on which jCodec will encode video
          videoProcessingThread = new HandlerThread("JCodecThread");
          videoProcessingThread.start();
          videoProcessingHandler = new JCodecEncoderHandler(videoProcessingThread.getLooper(), outputFile);
          Log.d("BAT", "JCodecThread started");
        } else {
          //If isRecording is true we will switch to no record to stop the thread
          videoProcessingHandler.sendEmptyMessage(1);
          //Quit safely so all pending messages get processed
          videoProcessingThread.quitSafely();
          videoProcessingThread = null;
          videoProcessingHandler = null;
          Log.d("BAT", "JCodecThread quit safely and join");
        }

        isRecording = !isRecording;
        if (isRecording) {
          recordButton.setText("Stop Recording");
        } else {
          recordButton.setText("Start Recording");
        }
      }
    });
  }

  /**
   * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
   * on this device.
   *
   * <p>Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
   *
   * <p>Finishes the activity if Sceneform can not run
   */
  public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
    if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
      Log.e(TAG, "Sceneform requires Android N or later");
      Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show();
      activity.finish();
      return false;
    }
    String openGlVersionString = ((ActivityManager) activity.getSystemService(
        Context.ACTIVITY_SERVICE)).getDeviceConfigurationInfo().getGlEsVersion();
    if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
      Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
      Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
          .show();
      activity.finish();
      return false;
    }
    return true;
  }

  @Override public void onUpdate(FrameTime frameTime) {
    if (isRecording) {
      if (messages > 0) {
        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bitmap = Bitmap.createBitmap(arFragment.getArSceneView().getWidth(),
            arFragment.getArSceneView().getHeight(), conf);

        PixelCopy.request(arFragment.getArSceneView(), bitmap,
            new PixelCopy.OnPixelCopyFinishedListener() {
              @Override public void onPixelCopyFinished(int copyResult) {
                if (videoProcessingHandler != null && messages > 0) {
                  Bundle bundle = new Bundle();
                  bundle.putParcelable("Bitmap", bitmap);
                  Message message = Message.obtain();
                  message.what = 0;
                  message.setData(bundle);

                  videoProcessingHandler.sendMessage(message);
                  messages--;
                  Log.d("BAT", "videoProcessingHandler POST");
                }
              }
            }, videoProcessingHandler);
      } else {
        //Finish encoder after all Bitmaps processed
        //TODO: This will be called multiple times after all messages have been processed, find a better ways to process this
        videoProcessingHandler.sendEmptyMessage(1);
        Log.d("BAT", "videoProcessingHandler.sendEmptyMessage(1);");
      }
    }
  }

  private class JCodecEncoderHandler extends Handler {

    private SeekableByteChannel out;
    private AndroidSequenceEncoder encoder;
    //private boolean finishingEncoder;
    //TODO return this flag and it's code so the app doesn't crash after finishing encoding,
    //TODO I left it like this for now so I know the encoder has finished

    JCodecEncoderHandler(Looper looper, File outputFile) {
      super(looper);

      //Prepare the video encoder
      try {
        out = NIOUtils.writableChannel(outputFile);
        encoder = new AndroidSequenceEncoder(out, Rational.R(25, 1));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    @Override public void handleMessage(Message msg) {
      try {
        if (msg.what == 0) {
          //get the bitmap and encode it
          Bundle bundle = msg.getData();
          Bitmap bitmap = bundle.getParcelable("Bitmap");

          encoder.encodeImage(bitmap);
        } else if (msg.what == 1) {
          //finish the encoder
          //if(!finishingEncoder){
            encoder.finish();
            //finishingEncoder = true;
          //}
          NIOUtils.closeQuietly(out);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
