// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.installations;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_API_KEY;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_APP_ID_1;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_AUTH_TOKEN;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_AUTH_TOKEN_2;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_AUTH_TOKEN_3;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_AUTH_TOKEN_4;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_CREATION_TIMESTAMP_2;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_FID_1;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_INSTALLATION_RESPONSE;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_INSTALLATION_RESPONSE_WITH_IID;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_INSTANCE_ID_1;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_PROJECT_ID;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_REFRESH_TOKEN;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_TOKEN_EXPIRATION_TIMESTAMP;
import static com.google.firebase.installations.FisAndroidTestConstants.TEST_TOKEN_RESULT;
import static com.google.firebase.installations.local.PersistedInstallationEntrySubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.installations.FirebaseInstallationsException.Status;
import com.google.firebase.installations.local.IidStore;
import com.google.firebase.installations.local.PersistedInstallation;
import com.google.firebase.installations.local.PersistedInstallation.RegistrationStatus;
import com.google.firebase.installations.local.PersistedInstallationEntry;
import com.google.firebase.installations.remote.FirebaseInstallationServiceClient;
import com.google.firebase.installations.remote.InstallationResponse;
import com.google.firebase.installations.remote.InstallationResponse.ResponseCode;
import com.google.firebase.installations.remote.TokenResult;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.mockito.AdditionalAnswers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FirebaseInstallationsInstrumentedTest {
  private FirebaseApp firebaseApp;
  private ExecutorService executor;
  private PersistedInstallation persistedInstallation;
  @Mock private FirebaseInstallationServiceClient mockBackend;
  @Mock private IidStore mockIidStore;
  @Mock private RandomFidGenerator mockFidGenerator;

  private static final PersistedInstallationEntry REGISTERED_INSTALLATION_ENTRY =
      PersistedInstallationEntry.builder()
          .setFirebaseInstallationId(TEST_FID_1)
          .setAuthToken(TEST_AUTH_TOKEN)
          .setRefreshToken(TEST_REFRESH_TOKEN)
          .setTokenCreationEpochInSecs(TEST_CREATION_TIMESTAMP_2)
          .setExpiresInSecs(TEST_TOKEN_EXPIRATION_TIMESTAMP)
          .setRegistrationStatus(PersistedInstallation.RegistrationStatus.REGISTERED)
          .build();

  private FirebaseInstallations firebaseInstallations;
  private Utils utils;
  private FakeCalendar fakeCalendar;

  @Before
  public void setUp() throws FirebaseException, IOException {
    MockitoAnnotations.initMocks(this);
    FirebaseApp.clearInstancesForTest();
    executor = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    fakeCalendar = new FakeCalendar(5000000L);
    firebaseApp =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            new FirebaseOptions.Builder()
                .setApplicationId(TEST_APP_ID_1)
                .setProjectId(TEST_PROJECT_ID)
                .setApiKey(TEST_API_KEY)
                .build());
    persistedInstallation = new PersistedInstallation(firebaseApp);
    persistedInstallation.clearForTesting();

    utils = new Utils(fakeCalendar);
    firebaseInstallations =
        new FirebaseInstallations(
            executor,
            firebaseApp,
            mockBackend,
            persistedInstallation,
            utils,
            mockIidStore,
            mockFidGenerator);

    when(mockFidGenerator.createRandomFid()).thenReturn(TEST_FID_1);
  }

  @After
  public void cleanUp() {
    persistedInstallation.clearForTesting();
    try {
      executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {

    }
  }

  /**
   * Check the id generation process when there is no network. There are three cases:
   *
   * <ul>
   *   <li>no iid -> generate a new fid
   *   <li>iid present -> make that iid into a fid
   *   <li>fid generated -> return that fid
   * </ul>
   */
  @Test
  public void testGetId_noNetwork_noIid() throws Exception {
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());
    when(mockIidStore.readIid()).thenReturn(null);

    // Do the actual getId() call under test. Confirm that it returns a generated FID and
    // and that the FID was written to storage.
    // Confirm both that it returns the expected ID, as does reading the prefs from storage.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);
    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(entryValue).hasFid(TEST_FID_1);

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // The storage should still have the same ID and the status should indicate that the
    // fid is registered.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.UNREGISTERED);
  }

  @Test
  public void testGetId_noNetwork_iidPresent() throws Exception {
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());
    when(mockIidStore.readIid()).thenReturn(TEST_INSTANCE_ID_1);

    // Do the actual getId() call under test. Confirm that it returns a generated FID and
    // and that the FID was written to storage.
    // Confirm both that it returns the expected ID, as does reading the prefs from storage.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_INSTANCE_ID_1);
    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(entryValue).hasFid(TEST_INSTANCE_ID_1);

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // The storage should still have the same ID and the status should indicate that the
    // fid is registered.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_INSTANCE_ID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.UNREGISTERED);
  }

  @Test
  public void testGetId_noNetwork_fidAlreadyGenerated() throws Exception {
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());

    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid("generatedFid"));

    // Do the actual getId() call under test. Confirm that it returns the already generated FID.
    // Confirm both that it returns the expected ID, as does reading the prefs from storage.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo("generatedFid");

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // The storage should still have the same ID and the status should indicate that the
    // fid is registered.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid("generatedFid");
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.UNREGISTERED);
  }

  /**
   * Checks that if we have a registered fid then the fid is returned and no backend calls are made.
   */
  @Test
  public void testGetId_ValidIdAndToken_NoBackendCalls() throws Exception {
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // No exception, means success.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);

    // getId() returns fid immediately but registers fid asynchronously.  Waiting for half a second
    // while we mock fid registration. We dont send an actual request to FIS in tests.
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // check that the mockClient didn't get invoked at all, since the fid is already registered
    // and the authtoken is present and not expired
    verifyZeroInteractions(mockBackend);

    // check that the fid is still the expected one and is registered
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTERED);
  }

  /**
   * Checks that if we have an unregistered fid that the fid gets registered with the backend and no
   * other calls are made.
   */
  @Test
  public void testGetId_UnRegisteredId_IssueCreateIdCall() throws Exception {
    when(mockBackend.createFirebaseInstallation(
            anyString(), matches(TEST_FID_1), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid(TEST_FID_1));

    // No exception, means success.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);

    // getId() returns fid immediately but registers fid asynchronously.  Waiting for half a second
    // while we mock fid registration. We dont send an actual request to FIS in tests.
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // check that the mockClient didn't get invoked at all, since the fid is already registered
    // and the authtoken is present and not expired
    verify(mockBackend)
        .createFirebaseInstallation(anyString(), matches(TEST_FID_1), anyString(), anyString());
    verify(mockBackend, never())
        .generateAuthToken(anyString(), anyString(), anyString(), anyString());

    // check that the fid is still the expected one and is registered
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTERED);
  }

  @Test
  public void testGetId_migrateIid_successful() throws Exception {
    when(mockIidStore.readIid()).thenReturn(TEST_INSTANCE_ID_1);
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE_WITH_IID);

    // Do the actual getId() call under test.
    // Confirm both that it returns the expected ID, as does reading the prefs from storage.
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_INSTANCE_ID_1);
    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(entryValue).hasFid(TEST_INSTANCE_ID_1);

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // The storage should still have the same ID and the status should indicate that the
    // fid si registered.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_INSTANCE_ID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTERED);
  }

  @Test
  public void testGetId_multipleCalls_sameFIDReturned() throws Exception {
    when(mockIidStore.readIid()).thenReturn(null);
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);

    // Call getId multiple times
    Task<String> task1 = firebaseInstallations.getId();
    Task<String> task2 = firebaseInstallations.getId();
    Tasks.await(Tasks.whenAllComplete(task1, task2));
    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    assertWithMessage("Persisted Fid of Task1 doesn't match.")
        .that(task1.getResult())
        .isEqualTo(TEST_FID_1);
    assertWithMessage("Persisted Fid of Task2 doesn't match.")
        .that(task2.getResult())
        .isEqualTo(TEST_FID_1);
    verify(mockBackend, times(1))
        .createFirebaseInstallation(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_APP_ID_1);
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTERED);
  }

  /**
   * Checks that if the server rejects a FID during registration the SDK will use the fid in the
   * response as the new fid.
   */
  @Test
  public void testGetId_unregistered_replacesFidWithResponse() throws Exception {
    // Update local storage with installation entry that has invalid fid.
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid("tobereplaced"));
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);

    // The first call will return the existing FID, "tobereplaced"
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo("tobereplaced");

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // The next call should return the FID that was returned by the server
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);
  }

  /**
   * A registration that fails with a SERVER_ERROR will cause the FID to be put into the error
   * state.
   */
  @Test
  public void testGetId_ServerError_UnregisteredFID() throws Exception {
    // start with an unregistered fid
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid(TEST_FID_1));

    // have the server return a server error for the registration
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            InstallationResponse.builder().setResponseCode(ResponseCode.BAD_CONFIG).build());

    // do a getId(), the unregistered TEST_FID_1 should be returned
    assertWithMessage("getId Task failed.")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);

    // Waiting for Task that registers FID on the FIS Servers.
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // We expect that the server error will cause the FID to be put into the error state.
    // There is nothing more we can do.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTER_ERROR);
  }

  /**
   * A registration that fails with an IOException will not cause the FID to be put into the error
   * state.
   */
  @Test
  public void testGetId_fidRegistrationUncheckedException_statusUpdated() throws Exception {
    // set initial state to having an unregistered FID
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid(TEST_FID_1));

    // Mocking unchecked exception on FIS createFirebaseInstallation
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());

    String fid = Tasks.await(firebaseInstallations.getId());
    assertEquals("fid doesn't match expected", TEST_FID_1, fid);

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // We expect that the IOException will cause the request to fail, but it will not
    // cause the FID to be put into the error state because we expect this to eventually succeed.
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.UNREGISTERED);
  }

  @Test
  public void testGetId_expiredAuthTokenUncheckedException_statusUpdated() throws Exception {
    // Start with a registered FID
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // Move the time forward by the token expiration time.
    fakeCalendar.advanceTimeBySeconds(TEST_TOKEN_EXPIRATION_TIMESTAMP);

    // Mocking unchecked exception on FIS generateAuthToken
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IOException());

    assertWithMessage("getId Task failed")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);

    // Waiting for Task that generates auth token with the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // Validate that registration status is still REGISTER
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasFid(TEST_FID_1);
    assertThat(updatedInstallationEntry).hasRegistrationStatus(RegistrationStatus.REGISTERED);
  }

  /**
   * The FID is successfully registered but the token is expired. A getId will cause the token to be
   * refreshed in the background.
   */
  @Test
  public void testGetId_expiredAuthToken_refreshesAuthToken() throws Exception {
    // Start with a registered FID
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // Make the server generateAuthToken() call return a refreshed token
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_TOKEN_RESULT);

    // Move the time forward by the token expiration time.
    fakeCalendar.advanceTimeBySeconds(TEST_TOKEN_EXPIRATION_TIMESTAMP);

    // Get the ID, which should cause the SDK to realize that the auth token is expired and
    // kick off a refresh of the token.
    assertWithMessage("getId Task failed")
        .that(Tasks.await(firebaseInstallations.getId()))
        .isEqualTo(TEST_FID_1);

    // Waiting for Task that registers FID on the FIS Servers
    executor.awaitTermination(500, TimeUnit.MILLISECONDS);

    // Check that the token has been refreshed
    assertWithMessage("auth token is not what is expected after the refresh")
        .that(
            Tasks.await(
                    firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH))
                .getToken())
        .isEqualTo(TEST_AUTH_TOKEN_2);

    verify(mockBackend, never())
        .createFirebaseInstallation(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_APP_ID_1);
    verify(mockBackend, times(1))
        .generateAuthToken(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_REFRESH_TOKEN);
  }

  @Test
  public void testGetAuthToken_fidDoesNotExist_successful() throws Exception {
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);
    Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH));

    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(entryValue).hasAuthToken(TEST_AUTH_TOKEN);
  }

  @Test
  public void testGetAuthToken_fidExists_successful() throws Exception {
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    InstallationTokenResult installationTokenResult =
        Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH));

    assertWithMessage("Persisted Auth Token doesn't match")
        .that(installationTokenResult.getToken())
        .isEqualTo(TEST_AUTH_TOKEN);
    verify(mockBackend, never())
        .generateAuthToken(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_REFRESH_TOKEN);
  }

  @Test
  public void testGetAuthToken_expiredAuthToken_fetchedNewTokenFromFIS() throws Exception {
    // start with a registered FID and valid auth token
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(REGISTERED_INSTALLATION_ENTRY);

    // Move the time forward by the token expiration time.
    fakeCalendar.advanceTimeBySeconds(TEST_TOKEN_EXPIRATION_TIMESTAMP);

    // have the server respond with a new token
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_TOKEN_RESULT);

    InstallationTokenResult installationTokenResult =
        Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH));

    assertWithMessage("Persisted Auth Token doesn't match")
        .that(installationTokenResult.getToken())
        .isEqualTo(TEST_AUTH_TOKEN_2);
  }

  @Test
  public void testGetToken_unregisteredFid_fetchedNewTokenFromFIS() throws Exception {
    // Update local storage with a unregistered installation entry to validate that getToken
    // calls getId to ensure FID registration and returns a valid auth token.
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid(TEST_FID_1));
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);

    InstallationTokenResult installationTokenResult =
        Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH));

    assertWithMessage("Persisted Auth Token doesn't match")
        .that(installationTokenResult.getToken())
        .isEqualTo(TEST_AUTH_TOKEN);
  }

  @Test
  public void testGetAuthToken_authError_persistedInstallationCleared() throws Exception {
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // Mocks error during auth token generation
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            TokenResult.builder().setResponseCode(TokenResult.ResponseCode.AUTH_ERROR).build());

    // Expect exception
    try {
      Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.FORCE_REFRESH));
      fail("the getAuthToken() call should have failed due to Auth Error.");
    } catch (ExecutionException expected) {
      assertWithMessage("Exception class doesn't match")
          .that(expected)
          .hasCauseThat()
          .isInstanceOf(IOException.class);
    }

    assertTrue(persistedInstallation.readPersistedInstallationEntryValue().isNotGenerated());
  }

  // /**
  //  * Check that a call to generateAuthToken(FORCE_REFRESH) fails if the backend client call
  //  * fails.
  //  */
  @Test
  public void testGetAuthToken_serverError_failure() throws Exception {
    // start the test with a registered FID
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // have the backend fail when generateAuthToken is invoked.
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            TokenResult.builder().setResponseCode(TokenResult.ResponseCode.BAD_CONFIG).build());

    // Make the forced getAuthToken call, which should fail.
    try {
      Tasks.await(firebaseInstallations.getToken(FirebaseInstallationsApi.FORCE_REFRESH));
      fail(
          "getAuthToken() succeeded but should have failed due to the BAD_CONFIG error "
              + "returned by the network call.");
    } catch (ExecutionException expected) {
      assertWithMessage("Exception class doesn't match")
          .that(expected)
          .hasCauseThat()
          .isInstanceOf(FirebaseInstallationsException.class);
      assertWithMessage("Exception status doesn't match")
          .that(((FirebaseInstallationsException) expected.getCause()).getStatus())
          .isEqualTo(Status.BAD_CONFIG);
    }
  }

  @Test
  public void testGetAuthToken_multipleCallsDoNotForceRefresh_fetchedNewTokenOnce()
      throws Exception {
    // start with a valid fid and authtoken
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // Make the server generateAuthToken() call return a refreshed token
    when(mockBackend.generateAuthToken(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_TOKEN_RESULT);

    // expire the authtoken by advancing the clock
    fakeCalendar.advanceTimeBySeconds(TEST_TOKEN_EXPIRATION_TIMESTAMP);

    // Call getToken multiple times with DO_NOT_FORCE_REFRESH option
    Task<InstallationTokenResult> task1 =
        firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH);
    Task<InstallationTokenResult> task2 =
        firebaseInstallations.getToken(FirebaseInstallationsApi.DO_NOT_FORCE_REFRESH);

    Tasks.await(Tasks.whenAllComplete(task1, task2));

    assertWithMessage("Persisted Auth Token doesn't match")
        .that(task1.getResult().getToken())
        .isEqualTo(TEST_AUTH_TOKEN_2);
    assertWithMessage("Persisted Auth Token doesn't match")
        .that(task2.getResult().getToken())
        .isEqualTo(TEST_AUTH_TOKEN_2);
    verify(mockBackend, times(1))
        .generateAuthToken(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_REFRESH_TOKEN);
  }

  @Test
  public void testGetAuthToken_multipleCallsForceRefresh_fetchedNewTokenTwice() throws Exception {
    // start with a valid fid and authtoken
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withRegisteredFid(
            TEST_FID_1,
            TEST_REFRESH_TOKEN,
            utils.currentTimeInSecs(),
            TEST_AUTH_TOKEN,
            TEST_TOKEN_EXPIRATION_TIMESTAMP));

    // Use a mock ServiceClient for network calls with delay(500ms) to ensure first task is not
    // completed before the second task starts. Hence, we can test multiple calls to getToken()
    // and verify one task waits for another task to complete.

    doAnswer(
            AdditionalAnswers.answersWithDelay(
                500,
                (unused) ->
                    TokenResult.builder()
                        .setToken(TEST_AUTH_TOKEN_3)
                        .setTokenExpirationTimestamp(TEST_TOKEN_EXPIRATION_TIMESTAMP)
                        .setResponseCode(TokenResult.ResponseCode.OK)
                        .build()))
        .doAnswer(
            AdditionalAnswers.answersWithDelay(
                500,
                (unused) ->
                    TokenResult.builder()
                        .setToken(TEST_AUTH_TOKEN_4)
                        .setTokenExpirationTimestamp(TEST_TOKEN_EXPIRATION_TIMESTAMP)
                        .setResponseCode(TokenResult.ResponseCode.OK)
                        .build()))
        .when(mockBackend)
        .generateAuthToken(anyString(), anyString(), anyString(), anyString());

    // Call getToken multiple times with FORCE_REFRESH option.
    Task<InstallationTokenResult> task1 =
        firebaseInstallations.getToken(FirebaseInstallationsApi.FORCE_REFRESH);
    Task<InstallationTokenResult> task2 =
        firebaseInstallations.getToken(FirebaseInstallationsApi.FORCE_REFRESH);
    Tasks.await(Tasks.whenAllComplete(task1, task2));

    // As we cannot ensure which task got executed first, verifying with both expected values
    assertWithMessage("Persisted Auth Token doesn't match")
        .that(task1.getResult().getToken())
        .isEqualTo(TEST_AUTH_TOKEN_3);
    assertWithMessage("Persisted Auth Token doesn't match")
        .that(task2.getResult().getToken())
        .isEqualTo(TEST_AUTH_TOKEN_3);
    verify(mockBackend, times(1))
        .generateAuthToken(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_REFRESH_TOKEN);
    PersistedInstallationEntry updatedInstallationEntry =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(updatedInstallationEntry).hasAuthToken(TEST_AUTH_TOKEN_3);
  }

  @Test
  public void testDelete_registeredFID_successful() throws Exception {
    // Update local storage with a registered installation entry
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(REGISTERED_INSTALLATION_ENTRY);
    when(mockBackend.createFirebaseInstallation(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TEST_INSTALLATION_RESPONSE);

    Tasks.await(firebaseInstallations.delete());

    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertEquals(entryValue.getRegistrationStatus(), RegistrationStatus.NOT_GENERATED);
    verify(mockBackend, times(1))
        .deleteFirebaseInstallation(TEST_API_KEY, TEST_FID_1, TEST_PROJECT_ID, TEST_REFRESH_TOKEN);
  }

  @Test
  public void testDelete_unregisteredFID_successful() throws Exception {
    // Update local storage with a unregistered installation entry
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withUnregisteredFid(TEST_FID_1));

    Tasks.await(firebaseInstallations.delete());

    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertEquals(entryValue.getRegistrationStatus(), RegistrationStatus.NOT_GENERATED);
    verify(mockBackend, never())
        .deleteFirebaseInstallation(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void testDelete_emptyPersistedFidEntry_successful() throws Exception {
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(
        PersistedInstallationEntry.INSTANCE.withNoGeneratedFid());

    Tasks.await(firebaseInstallations.delete());

    PersistedInstallationEntry entryValue =
        persistedInstallation.readPersistedInstallationEntryValue();
    assertThat(entryValue).hasRegistrationStatus(RegistrationStatus.NOT_GENERATED);
    verify(mockBackend, never())
        .deleteFirebaseInstallation(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void testDelete_serverError_badConfig() throws Exception {
    // Update local storage with a registered installation entry
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(REGISTERED_INSTALLATION_ENTRY);

    doThrow(new FirebaseException("Server Error"))
        .when(mockBackend)
        .deleteFirebaseInstallation(anyString(), anyString(), anyString(), anyString());

    // Expect exception
    try {
      Tasks.await(firebaseInstallations.delete());
      fail("firebaseInstallations.delete() failed due to Server Error.");
    } catch (ExecutionException expected) {
      assertWithMessage("Exception class doesn't match")
          .that(expected)
          .hasCauseThat()
          .isInstanceOf(FirebaseInstallationsException.class);
      assertWithMessage("Exception status doesn't match")
          .that(((FirebaseInstallationsException) expected.getCause()).getStatus())
          .isEqualTo(Status.BAD_CONFIG);
      PersistedInstallationEntry entryValue =
          persistedInstallation.readPersistedInstallationEntryValue();
      assertThat(entryValue).isEqualTo(REGISTERED_INSTALLATION_ENTRY);
    }
  }

  @Test
  public void testDelete_networkError() throws Exception {
    // Update local storage with a registered installation entry
    persistedInstallation.insertOrUpdatePersistedInstallationEntry(REGISTERED_INSTALLATION_ENTRY);

    doThrow(new IOException())
        .when(mockBackend)
        .deleteFirebaseInstallation(anyString(), anyString(), anyString(), anyString());

    // Expect exception
    try {
      Tasks.await(firebaseInstallations.delete());
      fail("firebaseInstallations.delete() failed due to a Network Error.");
    } catch (ExecutionException expected) {
      assertWithMessage("Exception class doesn't match")
          .that(expected)
          .hasCauseThat()
          .isInstanceOf(IOException.class);
      PersistedInstallationEntry entryValue =
          persistedInstallation.readPersistedInstallationEntryValue();
      assertThat(entryValue).isEqualTo(REGISTERED_INSTALLATION_ENTRY);
    }
  }
}
