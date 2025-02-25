package org.thoughtcrime.securesms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.omh.android.maps.api.presentation.fragments.OmhMapFragment;
import com.omh.android.maps.api.presentation.interfaces.maps.OmhMap;
import com.omh.android.maps.api.presentation.models.OmhCoordinate;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.location.SignalMapView;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Allows selection of an address from a google map.
 * <p>
 * Based on https://github.com/suchoX/PlacePicker
 */
public final class PlacePickerActivity extends AppCompatActivity {

  private static final String TAG = Log.tag(PlacePickerActivity.class);

  // If it cannot load location for any reason, it defaults to the prime meridian.
  private static final OmhCoordinate PRIME_MERIDIAN = new OmhCoordinate(51.4779, -0.0015);
  private static final String ADDRESS_INTENT = "ADDRESS";
  private static final float  ZOOM           = 17.0f;

  private static final int                   ANIMATION_DURATION     = 250;
  private static final OvershootInterpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator();
  public  static final String                KEY_CHAT_COLOR         = "chat_color";

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  private SingleAddressBottomSheet bottomSheet;
  private Address                  currentAddress;
  private OmhCoordinate            initialLocation;
  private OmhCoordinate            currentLocation = new OmhCoordinate(0, 0);
  private AddressLookup            addressLookup;
  private OmhMap                   omhMap;

  public static void startActivityForResultAtCurrentLocation(@NonNull Fragment fragment, int requestCode, @ColorInt int chatColor) {
    fragment.startActivityForResult(new Intent(fragment.requireActivity(), PlacePickerActivity.class).putExtra(KEY_CHAT_COLOR, chatColor), requestCode);
  }

  public static AddressData addressFromData(@NonNull Intent data) {
    return data.getParcelableExtra(ADDRESS_INTENT);
  }

  @SuppressLint("MissingInflatedId")
  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dynamicTheme.onCreate(this);
    
    setContentView(R.layout.activity_place_picker);

    bottomSheet      = findViewById(R.id.bottom_sheet);
    View markerImage = findViewById(R.id.marker_image_view);
    View fab         = findViewById(R.id.place_chosen_button);

    ViewCompat.setBackgroundTintList(fab, ColorStateList.valueOf(getIntent().getIntExtra(KEY_CHAT_COLOR, Color.RED)));
    fab.setOnClickListener(v -> finishWithAddress());

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      new LocationRetriever(this, this, location -> {
        setInitialLocation(new OmhCoordinate(location.getLatitude(), location.getLongitude()));
      }, () -> {
        Log.w(TAG, "Failed to get location.");
        setInitialLocation(PRIME_MERIDIAN);
      });
    } else {
      Log.w(TAG, "No location permissions");
      setInitialLocation(PRIME_MERIDIAN);
    }

    OmhMapFragment mapFragment = (OmhMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
    if (mapFragment == null) throw new AssertionError("No map fragment");

    mapFragment.getMapAsync(omhMap -> {
      setMap(omhMap);

      enableMyLocationButtonIfHaveThePermission(omhMap);

      omhMap.setOnCameraMoveStartedListener(i -> {
        markerImage.animate()
                   .translationY(-75f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        bottomSheet.hide();
      });

      omhMap.setOnCameraIdleListener(() -> {
        markerImage.animate()
                   .translationY(0f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        setCurrentLocation(omhMap.getCameraPositionCoordinate());
      });
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  private void setInitialLocation(@NonNull OmhCoordinate latLng) {
    initialLocation = latLng;

    moveMapToInitialIfPossible();
  }

  private void setMap(OmhMap omhMap) {
    this.omhMap = omhMap;

    moveMapToInitialIfPossible();
  }

  private void moveMapToInitialIfPossible() {
    if (initialLocation != null && omhMap != null) {
      Log.d(TAG, "Moving map to initial location");
      omhMap.moveCamera(initialLocation, ZOOM);
      setCurrentLocation(initialLocation);
    }
  }

  private void setCurrentLocation(OmhCoordinate location) {
    currentLocation = location;
    bottomSheet.showLoading();
    lookupAddress(location);
  }

  private void finishWithAddress() {
    Intent      returnIntent = new Intent();
    String      address      = currentAddress != null && currentAddress.getAddressLine(0) != null ? currentAddress.getAddressLine(0) : "";
    AddressData addressData  = new AddressData(currentLocation.getLatitude(), currentLocation.getLongitude(), address);

    SimpleProgressDialog.DismissibleDialog dismissibleDialog = SimpleProgressDialog.showDelayed(this);
    FrameLayout frameLayout = findViewById(R.id.map_view_container);
    SignalMapView.snapshot(currentLocation, frameLayout).addListener(new ListenableFuture.Listener<>() {
      @Override
      public void onSuccess(Bitmap result) {
        dismissibleDialog.dismiss();
        byte[] blob = BitmapUtil.toByteArray(result);
        Uri uri = BlobProvider.getInstance()
                              .forData(blob)
                              .withMimeType(MediaUtil.IMAGE_JPEG)
                              .createForSingleSessionInMemory();
        returnIntent.putExtra(ADDRESS_INTENT, addressData);
        returnIntent.setData(uri);
        setResult(RESULT_OK, returnIntent);
        finish();
      }

      @Override
      public void onFailure(ExecutionException e) {
        dismissibleDialog.dismiss();
        Log.e(TAG, "Failed to generate snapshot", e);
      }
    });
  }

  private void enableMyLocationButtonIfHaveThePermission(OmhMap omhMap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      omhMap.setMyLocationEnabled(true);
    }
  }

  private void lookupAddress(@Nullable OmhCoordinate target) {
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
    addressLookup = new AddressLookup();
    addressLookup.execute(target);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (addressLookup != null) {
      addressLookup.cancel(true);
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class AddressLookup extends AsyncTask<OmhCoordinate, Void, Address> {

    private final String TAG = Log.tag(AddressLookup.class);
    private final Geocoder geocoder;

    AddressLookup() {
      geocoder = new Geocoder(getApplicationContext(), Locale.getDefault());
    }

    @Override
    protected Address doInBackground(OmhCoordinate... latLngs) {
      if (latLngs.length == 0) return null;
      OmhCoordinate latLng = latLngs[0];
      if (latLng == null) return null;
      try {
        List<Address> result = geocoder.getFromLocation(latLng.getLatitude(), latLng.getLongitude(), 1);
        return !result.isEmpty() ? result.get(0) : null;
      } catch (IOException e) {
        Log.w(TAG, "Failed to get address from location", e);
        return null;
      }
    }

    @Override
    protected void onPostExecute(@Nullable Address address) {
      currentAddress = address;
      if (address != null) {
        bottomSheet.showResult(address.getLatitude(), address.getLongitude(), addressToShortString(address), addressToString(address));
      } else {
        bottomSheet.hide();
      }
    }
  }

  private static @NonNull String addressToString(@Nullable Address address) {
    return address != null ? address.getAddressLine(0) : "";
  }

  private static @NonNull String addressToShortString(@Nullable Address address) {
    if (address == null) return "";

    String   addressLine = address.getAddressLine(0);
    String[] split       = addressLine.split(",");

    if (split.length >= 3) {
      return split[1].trim() + ", " + split[2].trim();
    } else if (split.length == 2) {
      return split[1].trim();
    } else return split[0].trim();
  }
}
