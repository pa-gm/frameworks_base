/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.InstallSourceInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ApkSigningBlockUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IBinaryTransparencyService;
import com.android.internal.util.FrameworkStatsLog;

import libcore.util.HexEncoding;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @hide
 */
public class BinaryTransparencyService extends SystemService {
    private static final String TAG = "TransparencyService";
    private static final String EXTRA_SERVICE = "service";

    @VisibleForTesting
    static final String VBMETA_DIGEST_UNINITIALIZED = "vbmeta-digest-uninitialized";
    @VisibleForTesting
    static final String VBMETA_DIGEST_UNAVAILABLE = "vbmeta-digest-unavailable";
    @VisibleForTesting
    static final String SYSPROP_NAME_VBETA_DIGEST = "ro.boot.vbmeta.digest";

    @VisibleForTesting
    static final String BINARY_HASH_ERROR = "SHA256HashError";

    static final int MEASURE_APEX_AND_MODULES = 1;
    static final int MEASURE_PRELOADS = 2;
    static final int MEASURE_NEW_MBAS = 3;

    static final long RECORD_MEASUREMENTS_COOLDOWN_MS = 24 * 60 * 60 * 1000;

    @VisibleForTesting
    static final String BUNDLE_PACKAGE_INFO = "package-info";
    @VisibleForTesting
    static final String BUNDLE_CONTENT_DIGEST_ALGORITHM = "content-digest-algo";
    @VisibleForTesting
    static final String BUNDLE_CONTENT_DIGEST = "content-digest";

    static final String APEX_PRELOAD_LOCATION = "/system/apex/";
    static final String APEX_PRELOAD_LOCATION_ERROR = "could-not-be-determined";

    // used for indicating any type of error during MBA measurement
    static final int MBA_STATUS_ERROR = 0;
    // used for indicating factory condition preloads
    static final int MBA_STATUS_PRELOADED = 1;
    // used for indicating preloaded apps that are updated
    static final int MBA_STATUS_UPDATED_PRELOAD = 2;
    // used for indicating newly installed MBAs
    static final int MBA_STATUS_NEW_INSTALL = 3;
    // used for indicating newly installed MBAs that are updated (but unused currently)
    static final int MBA_STATUS_UPDATED_NEW_INSTALL = 4;

    private static final boolean DEBUG = true;     // set this to false upon submission

    private final Context mContext;
    private String mVbmetaDigest;
    // the system time (in ms) the last measurement was taken
    private long mMeasurementsLastRecordedMs;

    final class BinaryTransparencyServiceImpl extends IBinaryTransparencyService.Stub {

        @Override
        public String getSignedImageInfo() {
            return mVbmetaDigest;
        }

        @Override
        public List getApexInfo() {
            List<Bundle> results = new ArrayList<>();

            for (PackageInfo packageInfo : getCurrentInstalledApexs()) {
                Bundle apexMeasurement = measurePackage(packageInfo);
                results.add(apexMeasurement);
            }

            return results;
        }

        /**
         * A helper function to compute the SHA256 digest of APK package signer.
         * @param signingInfo The signingInfo of a package, usually {@link PackageInfo#signingInfo}.
         * @return an array of {@code String} representing hex encoded string of the
         *         SHA256 digest of APK signer(s). The number of signers will be reflected by the
         *         size of the array.
         *         However, {@code null} is returned if there is any error.
         */
        private String[] computePackageSignerSha256Digests(@Nullable SigningInfo signingInfo) {
            if (signingInfo == null) {
                Slog.e(TAG, "signingInfo is null");
                return null;
            }

            Signature[] packageSigners = signingInfo.getApkContentsSigners();
            List<String> resultList = new ArrayList<>();
            for (Signature packageSigner : packageSigners) {
                byte[] digest = PackageUtils.computeSha256DigestBytes(packageSigner.toByteArray());
                String digestHexString = HexEncoding.encodeToString(digest, false);
                resultList.add(digestHexString);
            }
            return resultList.toArray(new String[1]);
        }

        /**
         * Perform basic measurement (i.e. content digest) on a given package.
         * @param packageInfo The package to be measured.
         * @return a {@link android.os.Bundle} that packs the measurement result with the following
         *         keys: {@link #BUNDLE_PACKAGE_INFO},
         *               {@link #BUNDLE_CONTENT_DIGEST_ALGORITHM}
         *               {@link #BUNDLE_CONTENT_DIGEST}
         */
        private @NonNull Bundle measurePackage(PackageInfo packageInfo) {
            Bundle result = new Bundle();

            // compute content digest
            if (DEBUG) {
                Slog.d(TAG, "Computing content digest for " + packageInfo.packageName + " at "
                        + packageInfo.applicationInfo.sourceDir);
            }
            Map<Integer, byte[]> contentDigests = computeApkContentDigest(
                    packageInfo.applicationInfo.sourceDir);
            result.putParcelable(BUNDLE_PACKAGE_INFO, packageInfo);
            if (contentDigests == null) {
                Slog.d(TAG, "Failed to compute content digest for "
                        + packageInfo.applicationInfo.sourceDir);
                result.putInt(BUNDLE_CONTENT_DIGEST_ALGORITHM, 0);
                result.putByteArray(BUNDLE_CONTENT_DIGEST, null);
                return result;
            }

            // in this iteration, we'll be supporting only 2 types of digests:
            // CHUNKED_SHA256 and CHUNKED_SHA512.
            // And only one of them will be available per package.
            if (contentDigests.containsKey(ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256)) {
                Integer algorithmId = ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256;
                result.putInt(BUNDLE_CONTENT_DIGEST_ALGORITHM, algorithmId);
                result.putByteArray(BUNDLE_CONTENT_DIGEST, contentDigests.get(algorithmId));
            } else if (contentDigests.containsKey(
                    ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512)) {
                Integer algorithmId = ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512;
                result.putInt(BUNDLE_CONTENT_DIGEST_ALGORITHM, algorithmId);
                result.putByteArray(BUNDLE_CONTENT_DIGEST, contentDigests.get(algorithmId));
            } else {
                // TODO(b/259423111): considering putting the raw values for the algorithm & digest
                //  into the bundle to track potential other digest algorithms that may be in use
                result.putInt(BUNDLE_CONTENT_DIGEST_ALGORITHM, 0);
                result.putByteArray(BUNDLE_CONTENT_DIGEST, null);
            }

            return result;
        }


        /**
         * Measures and records digests for *all* covered binaries/packages.
         *
         * This method will be called in a Job scheduled to take measurements periodically.
         *
         * Packages that are covered so far are:
         * - all APEXs (introduced in Android T)
         * - all mainline modules (introduced in Android T)
         * - all preloaded apps and their update(s) (new in Android U)
         * - dynamically installed mobile bundled apps (MBAs) (new in Android U)
         *
         * @return a {@code List<Bundle>}. Each Bundle item contains values as
         *          defined by the return value of {@link #measurePackage(PackageInfo)}.
         */
        public List getMeasurementsForAllPackages() {
            List<Bundle> results = new ArrayList<>();
            PackageManager pm = mContext.getPackageManager();
            Set<String> packagesMeasured = new HashSet<>();

            // check if we should record the resulting measurements
            long currentTimeMs = System.currentTimeMillis();
            boolean record = false;
            if ((currentTimeMs - mMeasurementsLastRecordedMs) >= RECORD_MEASUREMENTS_COOLDOWN_MS) {
                Slog.d(TAG, "Measurement was last taken at " + mMeasurementsLastRecordedMs
                        + " and is now updated to: " + currentTimeMs);
                mMeasurementsLastRecordedMs = currentTimeMs;
                record = true;
            }

            // measure all APEXs first
            if (DEBUG) {
                Slog.d(TAG, "Measuring APEXs...");
            }
            for (PackageInfo packageInfo : getCurrentInstalledApexs()) {
                packagesMeasured.add(packageInfo.packageName);

                Bundle apexMeasurement = measurePackage(packageInfo);
                results.add(apexMeasurement);

                if (record) {
                    // compute digests of signing info
                    String[] signerDigestHexStrings = computePackageSignerSha256Digests(
                            packageInfo.signingInfo);

                    // log to Westworld
                    FrameworkStatsLog.write(FrameworkStatsLog.APEX_INFO_GATHERED,
                                            packageInfo.packageName,
                                            packageInfo.getLongVersionCode(),
                                            HexEncoding.encodeToString(apexMeasurement.getByteArray(
                                                    BUNDLE_CONTENT_DIGEST), false),
                                            apexMeasurement.getInt(BUNDLE_CONTENT_DIGEST_ALGORITHM),
                                            signerDigestHexStrings);
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages after considering APEXs.");
            }

            // proceed with all preloaded apps
            for (PackageInfo packageInfo : pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_FACTORY_ONLY
                            | PackageManager.GET_SIGNING_CERTIFICATES))) {
                if (packagesMeasured.contains(packageInfo.packageName)) {
                    continue;
                }
                packagesMeasured.add(packageInfo.packageName);

                int mba_status = MBA_STATUS_PRELOADED;
                if (packageInfo.signingInfo == null) {
                    Slog.d(TAG, "Preload " + packageInfo.packageName  + " at "
                            + packageInfo.applicationInfo.sourceDir + " has likely been updated.");
                    mba_status = MBA_STATUS_UPDATED_PRELOAD;

                    PackageInfo origPackageInfo = packageInfo;
                    try {
                        packageInfo = pm.getPackageInfo(packageInfo.packageName,
                                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL
                                        | PackageManager.GET_SIGNING_CERTIFICATES));
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.e(TAG, "Failed to obtain an updated PackageInfo of "
                                + origPackageInfo.packageName, e);
                        packageInfo = origPackageInfo;
                        mba_status = MBA_STATUS_ERROR;
                    }
                }


                Bundle packageMeasurement = measurePackage(packageInfo);
                results.add(packageMeasurement);

                if (record) {
                    // compute digests of signing info
                    String[] signerDigestHexStrings = computePackageSignerSha256Digests(
                            packageInfo.signingInfo);

                    // now we should have all the bits for the atom
                    /*  TODO: Uncomment and test after merging new atom definition.
                    FrameworkStatsLog.write(FrameworkStatsLog.MOBILE_BUNDLED_APP_INFO_GATHERED,
                            packageInfo.packageName,
                            packageInfo.getLongVersionCode(),
                            HexEncoding.encodeToString(packageMeasurement.getByteArray(
                                    BUNDLE_CONTENT_DIGEST), false),
                            packageMeasurement.getInt(BUNDLE_CONTENT_DIGEST_ALGORITHM),
                            signerDigestHexStrings, // signer_cert_digest
                            mba_status,                 // mba_status
                            null,                   // initiator
                            null,                   // initiator_signer_digest
                            null,                   // installer
                            null                    // originator
                    );
                     */
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages after considering preloads");
            }

            // lastly measure all newly installed MBAs
            for (PackageInfo packageInfo : getNewlyInstalledMbas()) {
                if (packagesMeasured.contains(packageInfo.packageName)) {
                    continue;
                }
                packagesMeasured.add(packageInfo.packageName);

                Bundle packageMeasurement = measurePackage(packageInfo);
                results.add(packageMeasurement);

                if (record) {
                    // compute digests of signing info
                    String[] signerDigestHexStrings = computePackageSignerSha256Digests(
                            packageInfo.signingInfo);

                    // then extract package's InstallSourceInfo
                    if (DEBUG) {
                        Slog.d(TAG, "Extracting InstallSourceInfo for " + packageInfo.packageName);
                    }
                    InstallSourceInfo installSourceInfo = getInstallSourceInfo(
                            packageInfo.packageName);
                    String initiator = null;
                    SigningInfo initiatorSignerInfo = null;
                    String[] initiatorSignerInfoDigest = null;
                    String installer = null;
                    String originator = null;

                    if (installSourceInfo != null) {
                        initiator = installSourceInfo.getInitiatingPackageName();
                        initiatorSignerInfo = installSourceInfo.getInitiatingPackageSigningInfo();
                        if (initiatorSignerInfo != null) {
                            initiatorSignerInfoDigest = computePackageSignerSha256Digests(
                                    initiatorSignerInfo);
                        }
                        installer = installSourceInfo.getInstallingPackageName();
                        originator = installSourceInfo.getOriginatingPackageName();
                    }

                    // we should now have all the info needed for the atom
                    /*  TODO: Uncomment and test after merging new atom definition.
                    FrameworkStatsLog.write(FrameworkStatsLog.MOBILE_BUNDLED_APP_INFO_GATHERED,
                            packageInfo.packageName,
                            packageInfo.getLongVersionCode(),
                            HexEncoding.encodeToString(packageMeasurement.getByteArray(
                                    BUNDLE_CONTENT_DIGEST), false),
                            packageMeasurement.getInt(BUNDLE_CONTENT_DIGEST_ALGORITHM),
                            signerDigestHexStrings,
                            MBA_STATUS_NEW_INSTALL,   // mba_status
                            initiator,
                            initiatorSignerInfoDigest,
                            installer,
                            originator
                    );
                     */
                }
            }
            if (DEBUG) {
                long timeSpentMeasuring = System.currentTimeMillis() - currentTimeMs;
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages altogether in " + timeSpentMeasuring + "ms");
            }

            return results;
        }

        /**
         * A wrapper around
         * {@link ApkSignatureVerifier#verifySignaturesInternal(ParseInput, String, int, boolean)}.
         * @param pathToApk The APK's installation path
         * @return a {@code Map<Integer, byte[]>} with algorithm type as the key and content
         *         digest as the value.
         *         a {@code null} is returned upon encountering any error.
         */
        private Map<Integer, byte[]> computeApkContentDigest(String pathToApk) {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            ParseResult<ApkSignatureVerifier.SigningDetailsWithDigests> parseResult =
                    ApkSignatureVerifier.verifySignaturesInternal(input,
                            pathToApk,
                            SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2, false);
            if (parseResult.isError()) {
                Slog.e(TAG, "Failed to compute content digest for "
                        + pathToApk + " due to: "
                        + parseResult.getErrorMessage());
                return null;
            }
            return parseResult.getResult().contentDigests;
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                                   @Nullable FileDescriptor out,
                                   @Nullable FileDescriptor err,
                                   @NonNull String[] args,
                                   @Nullable ShellCallback callback,
                                   @NonNull ResultReceiver resultReceiver) throws RemoteException {
            (new ShellCommand() {

                private int printSignedImageInfo() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean listAllPartitions = false;
                    String opt;

                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-a":
                                listAllPartitions = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    final String signedImageInfo = getSignedImageInfo();
                    pw.println("Image Info:");
                    pw.println(Build.FINGERPRINT);
                    pw.println(signedImageInfo);
                    pw.println("");

                    if (listAllPartitions) {
                        PackageManager pm = mContext.getPackageManager();
                        if (pm == null) {
                            pw.println("ERROR: Failed to obtain an instance of package manager.");
                            return -1;
                        }

                        pw.println("Other partitions:");
                        List<Build.Partition> buildPartitions = Build.getFingerprintedPartitions();
                        for (Build.Partition buildPartition : buildPartitions) {
                            pw.println("Name: " + buildPartition.getName());
                            pw.println("Fingerprint: " + buildPartition.getFingerprint());
                            pw.println("Build time (ms): " + buildPartition.getBuildTimeMillis());
                        }
                    }
                    return 0;
                }

                private void printPackageMeasurements(PackageInfo packageInfo,
                                                      final PrintWriter pw) {
                    Map<Integer, byte[]> contentDigests = computeApkContentDigest(
                            packageInfo.applicationInfo.sourceDir);
                    if (contentDigests == null) {
                        pw.println("ERROR: Failed to compute package content digest for "
                                + packageInfo.applicationInfo.sourceDir);
                        return;
                    }

                    for (Map.Entry<Integer, byte[]> entry : contentDigests.entrySet()) {
                        Integer algorithmId = entry.getKey();
                        byte[] contentDigest = entry.getValue();

                        // TODO(b/259348134): consider refactoring the following to a helper method
                        switch (algorithmId) {
                            case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256:
                                pw.print("CHUNKED_SHA256:");
                                break;
                            case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512:
                                pw.print("CHUNKED_SHA512:");
                                break;
                            case ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                                pw.print("VERITY_CHUNKED_SHA256:");
                                break;
                            case ApkSigningBlockUtils.CONTENT_DIGEST_SHA256:
                                pw.print("SHA256:");
                                break;
                            default:
                                pw.print("UNKNOWN_ALGO_ID(" + algorithmId + "):");
                        }
                        pw.print(HexEncoding.encodeToString(contentDigest, false));
                    }
                }

                private void printPackageInstallationInfo(PackageInfo packageInfo,
                                                          final PrintWriter pw) {
                    pw.println("--- Package Installation Info ---");
                    pw.println("Current install location: "
                            + packageInfo.applicationInfo.sourceDir);
                    if (packageInfo.applicationInfo.sourceDir.startsWith("/data/apex/")) {
                        String origPackageFilepath = getOriginalApexPreinstalledLocation(
                                packageInfo.packageName, packageInfo.applicationInfo.sourceDir);
                        pw.println("|--> Pre-installed package install location: "
                                + origPackageFilepath);

                        // TODO(b/259347186): revive this with the proper cmd options.
                        /*
                        String digest = PackageUtils.computeSha256DigestForLargeFile(
                        origPackageFilepath, PackageUtils.createLargeFileBuffer());
                         */

                        Map<Integer, byte[]> contentDigests = computeApkContentDigest(
                                origPackageFilepath);
                        if (contentDigests == null) {
                            pw.println("ERROR: Failed to compute package content digest for "
                                    + origPackageFilepath);
                        } else {
                            // TODO(b/259348134): consider refactoring this to a helper method
                            for (Map.Entry<Integer, byte[]> entry : contentDigests.entrySet()) {
                                Integer algorithmId = entry.getKey();
                                byte[] contentDigest = entry.getValue();
                                pw.print("|--> Pre-installed package content digest algorithm: ");
                                switch (algorithmId) {
                                    case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256:
                                        pw.print("CHUNKED_SHA256");
                                        break;
                                    case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512:
                                        pw.print("CHUNKED_SHA512");
                                        break;
                                    case ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                                        pw.print("VERITY_CHUNKED_SHA256");
                                        break;
                                    case ApkSigningBlockUtils.CONTENT_DIGEST_SHA256:
                                        pw.print("SHA256");
                                        break;
                                    default:
                                        pw.print("UNKNOWN");
                                }
                                pw.print("\n");
                                pw.print("|--> Pre-installed package content digest: ");
                                pw.print(HexEncoding.encodeToString(contentDigest, false));
                                pw.print("\n");
                            }
                        }
                    }
                    pw.println("First install time (ms): " + packageInfo.firstInstallTime);
                    pw.println("Last update time (ms): " + packageInfo.lastUpdateTime);
                    boolean isPreloaded = (packageInfo.firstInstallTime
                            == packageInfo.lastUpdateTime);
                    pw.println("Is preloaded: " + isPreloaded);

                    InstallSourceInfo installSourceInfo = getInstallSourceInfo(
                            packageInfo.packageName);
                    if (installSourceInfo == null) {
                        pw.println("ERROR: Unable to obtain installSourceInfo of "
                                + packageInfo.packageName);
                    } else {
                        pw.println("Installation initiated by: "
                                + installSourceInfo.getInitiatingPackageName());
                        pw.println("Installation done by: "
                                + installSourceInfo.getInstallingPackageName());
                        pw.println("Installation originating from: "
                                + installSourceInfo.getOriginatingPackageName());
                    }

                    if (packageInfo.isApex) {
                        pw.println("Is an active APEX: " + packageInfo.isActiveApex);
                    }
                }

                private void printPackageSignerDetails(SigningInfo signerInfo,
                                                       final PrintWriter pw) {
                    if (signerInfo == null) {
                        pw.println("ERROR: Package's signingInfo is null.");
                        return;
                    }
                    pw.println("--- Package Signer Info ---");
                    pw.println("Has multiple signers: " + signerInfo.hasMultipleSigners());
                    Signature[] packageSigners = signerInfo.getApkContentsSigners();
                    for (Signature packageSigner : packageSigners) {
                        byte[] packageSignerDigestBytes =
                                PackageUtils.computeSha256DigestBytes(packageSigner.toByteArray());
                        String packageSignerDigestHextring =
                                HexEncoding.encodeToString(packageSignerDigestBytes, false);
                        pw.println("Signer cert's SHA256-digest: " + packageSignerDigestHextring);
                        try {
                            PublicKey publicKey = packageSigner.getPublicKey();
                            pw.println("Signing key algorithm: " + publicKey.getAlgorithm());
                        } catch (CertificateException e) {
                            Slog.e(TAG,
                                    "Failed to obtain public key of signer for cert with hash: "
                                    + packageSignerDigestHextring);
                            e.printStackTrace();
                        }
                    }

                }

                private void printModuleDetails(ModuleInfo moduleInfo, final PrintWriter pw) {
                    pw.println("--- Module Details ---");
                    pw.println("Module name: " + moduleInfo.getName());
                    pw.println("Module visibility: "
                            + (moduleInfo.isHidden() ? "hidden" : "visible"));
                }

                private void printAppDetails(PackageInfo packageInfo,
                                             boolean printLibraries,
                                             final PrintWriter pw) {
                    pw.println("--- App Details ---");
                    pw.println("Name: " + packageInfo.applicationInfo.name);
                    pw.println("Label: " + mContext.getPackageManager().getApplicationLabel(
                            packageInfo.applicationInfo));
                    pw.println("Description: " + packageInfo.applicationInfo.loadDescription(
                            mContext.getPackageManager()));
                    pw.println("Has code: " + packageInfo.applicationInfo.hasCode());
                    pw.println("Is enabled: " + packageInfo.applicationInfo.enabled);
                    pw.println("Is suspended: " + ((packageInfo.applicationInfo.flags
                                                    & ApplicationInfo.FLAG_SUSPENDED) != 0));

                    pw.println("Compile SDK version: " + packageInfo.compileSdkVersion);
                    pw.println("Target SDK version: "
                            + packageInfo.applicationInfo.targetSdkVersion);

                    pw.println("Is privileged: "
                            + packageInfo.applicationInfo.isPrivilegedApp());
                    pw.println("Is a stub: " + packageInfo.isStub);
                    pw.println("Is a core app: " + packageInfo.coreApp);
                    pw.println("SEInfo: " + packageInfo.applicationInfo.seInfo);
                    pw.println("Component factory: "
                            + packageInfo.applicationInfo.appComponentFactory);
                    pw.println("Process name: " + packageInfo.applicationInfo.processName);
                    pw.println("Task affinity : " + packageInfo.applicationInfo.taskAffinity);
                    pw.println("UID: " + packageInfo.applicationInfo.uid);
                    pw.println("Shared UID: " + packageInfo.sharedUserId);

                    if (printLibraries) {
                        pw.println("== App's Shared Libraries ==");
                        List<SharedLibraryInfo> sharedLibraryInfos =
                                packageInfo.applicationInfo.getSharedLibraryInfos();
                        if (sharedLibraryInfos == null || sharedLibraryInfos.isEmpty()) {
                            pw.println("<none>");
                        }

                        for (int i = 0; i < sharedLibraryInfos.size(); i++) {
                            SharedLibraryInfo sharedLibraryInfo = sharedLibraryInfos.get(i);
                            pw.println("  ++ Library #" + (i + 1) + " ++");
                            pw.println("  Lib name: " + sharedLibraryInfo.getName());
                            long libVersion = sharedLibraryInfo.getLongVersion();
                            pw.print("  Lib version: ");
                            if (libVersion == SharedLibraryInfo.VERSION_UNDEFINED) {
                                pw.print("undefined");
                            } else {
                                pw.print(libVersion);
                            }
                            pw.print("\n");

                            pw.println("  Lib package name (if available): "
                                    + sharedLibraryInfo.getPackageName());
                            pw.println("  Lib path: " + sharedLibraryInfo.getPath());
                            pw.print("  Lib type: ");
                            switch (sharedLibraryInfo.getType()) {
                                case SharedLibraryInfo.TYPE_BUILTIN:
                                    pw.print("built-in");
                                    break;
                                case SharedLibraryInfo.TYPE_DYNAMIC:
                                    pw.print("dynamic");
                                    break;
                                case SharedLibraryInfo.TYPE_STATIC:
                                    pw.print("static");
                                    break;
                                case SharedLibraryInfo.TYPE_SDK_PACKAGE:
                                    pw.print("SDK");
                                    break;
                                case SharedLibraryInfo.VERSION_UNDEFINED:
                                default:
                                    pw.print("undefined");
                                    break;
                            }
                            pw.print("\n");
                            pw.println("  Is a native lib: " + sharedLibraryInfo.isNative());
                        }
                    }

                }

                private int printAllApexs() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                                verbose = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    if (!verbose) {
                        pw.println("APEX Info [Format: package_name,package_version,"
                                // TODO(b/259347186): revive via special cmd line option
                                //+ "package_sha256_digest,"
                                + "content_digest_algorithm:content_digest]:");
                    }
                    for (PackageInfo packageInfo : getCurrentInstalledApexs()) {
                        if (verbose) {
                            pw.println("APEX Info [Format: package_name,package_version,"
                                    // TODO(b/259347186): revive via special cmd line option
                                    //+ "package_sha256_digest,"
                                    + "content_digest_algorithm:content_digest]:");
                        }
                        String packageName = packageInfo.packageName;
                        pw.print(packageName + ","
                                + packageInfo.getLongVersionCode() + ",");
                        printPackageMeasurements(packageInfo, pw);
                        pw.print("\n");

                        if (verbose) {
                            ModuleInfo moduleInfo;
                            try {
                                moduleInfo = pm.getModuleInfo(packageInfo.packageName, 0);
                                pw.println("Is a module: true");
                                printModuleDetails(moduleInfo, pw);
                            } catch (PackageManager.NameNotFoundException e) {
                                pw.println("Is a module: false");
                            }

                            printPackageInstallationInfo(packageInfo, pw);
                            printPackageSignerDetails(packageInfo.signingInfo, pw);
                            pw.println("");
                        }
                    }
                    return 0;
                }

                private int printAllModules() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                                verbose = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    if (!verbose) {
                        pw.println("Module Info [Format: package_name,package_version,"
                                // TODO(b/259347186): revive via special cmd line option
                                //+ "package_sha256_digest,"
                                + "content_digest_algorithm:content_digest]:");
                    }
                    for (ModuleInfo module : pm.getInstalledModules(PackageManager.MATCH_ALL)) {
                        String packageName = module.getPackageName();
                        if (verbose) {
                            pw.println("Module Info [Format: package_name,package_version,"
                                    // TODO(b/259347186): revive via special cmd line option
                                    //+ "package_sha256_digest,"
                                    + "content_digest_algorithm:content_digest]:");
                        }
                        try {
                            PackageInfo packageInfo = pm.getPackageInfo(packageName,
                                    PackageManager.MATCH_APEX
                                            | PackageManager.GET_SIGNING_CERTIFICATES);
                            //pw.print("package:");
                            pw.print(packageInfo.packageName + ",");
                            pw.print(packageInfo.getLongVersionCode() + ",");
                            printPackageMeasurements(packageInfo, pw);
                            pw.print("\n");

                            if (verbose) {
                                printModuleDetails(module, pw);
                                printPackageInstallationInfo(packageInfo, pw);
                                printPackageSignerDetails(packageInfo.signingInfo, pw);
                                pw.println("");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            pw.println(packageName
                                    + ",ERROR:Unable to find PackageInfo for this module.");
                            if (verbose) {
                                printModuleDetails(module, pw);
                                pw.println("");
                            }
                            continue;
                        }
                    }
                    return 0;
                }

                private int printAllMbas() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    boolean printLibraries = false;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                                verbose = true;
                                break;
                            case "-l":
                                printLibraries = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    if (!verbose) {
                        pw.println("MBA Info [Format: package_name,package_version,"
                                // TODO(b/259347186): revive via special cmd line option
                                //+ "package_sha256_digest,"
                                + "content_digest_algorithm:content_digest]:");
                    }
                    for (PackageInfo packageInfo : getNewlyInstalledMbas()) {
                        if (verbose) {
                            pw.println("MBA Info [Format: package_name,package_version,"
                                    // TODO(b/259347186): revive via special cmd line option
                                    //+ "package_sha256_digest,"
                                    + "content_digest_algorithm:content_digest]:");
                        }
                        pw.print(packageInfo.packageName + ",");
                        pw.print(packageInfo.getLongVersionCode() + ",");
                        printPackageMeasurements(packageInfo, pw);
                        pw.print("\n");

                        if (verbose) {
                            printAppDetails(packageInfo, printLibraries, pw);
                            printPackageInstallationInfo(packageInfo, pw);
                            printPackageSignerDetails(packageInfo.signingInfo, pw);
                            pw.println("");
                        }
                    }
                    return 0;
                }

                // TODO(b/259347186): add option handling full file-based SHA256 digest
                private int printAllPreloads() {
                    final PrintWriter pw = getOutPrintWriter();

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        Slog.e(TAG, "Failed to obtain PackageManager.");
                        return -1;
                    }
                    List<PackageInfo> factoryApps = pm.getInstalledPackages(
                            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_FACTORY_ONLY));

                    pw.println("Preload Info [Format: package_name]");
                    for (PackageInfo packageInfo : factoryApps) {
                        pw.println(packageInfo.packageName);
                    }
                    return 0;
                }

                @Override
                public int onCommand(String cmd) {
                    if (cmd == null) {
                        return handleDefaultCommands(cmd);
                    }

                    final PrintWriter pw = getOutPrintWriter();
                    switch (cmd) {
                        case "get": {
                            final String infoType = getNextArg();
                            if (infoType == null) {
                                printHelpMenu();
                                return -1;
                            }

                            switch (infoType) {
                                case "image_info":
                                    return printSignedImageInfo();
                                case "apex_info":
                                    return printAllApexs();
                                case "module_info":
                                    return printAllModules();
                                case "mba_info":
                                    return printAllMbas();
                                case "preload_info":
                                    return printAllPreloads();
                                default:
                                    pw.println(String.format("ERROR: Unknown info type '%s'",
                                            infoType));
                                    return 1;
                            }
                        }
                        default:
                            return handleDefaultCommands(cmd);
                    }
                }

                private void printHelpMenu() {
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println("Transparency manager (transparency) commands:");
                    pw.println("    help");
                    pw.println("        Print this help text.");
                    pw.println("");
                    pw.println("    get image_info [-a]");
                    pw.println("        Print information about loaded image (firmware). Options:");
                    pw.println("            -a: lists all other identifiable partitions.");
                    pw.println("");
                    pw.println("    get apex_info [-v]");
                    pw.println("        Print information about installed APEXs on device.");
                    pw.println("            -v: lists more verbose information about each APEX.");
                    pw.println("");
                    pw.println("    get module_info [-v]");
                    pw.println("        Print information about installed modules on device.");
                    pw.println("            -v: lists more verbose information about each module.");
                    pw.println("");
                    pw.println("    get mba_info [-v] [-l]");
                    pw.println("        Print information about installed mobile bundle apps "
                               + "(MBAs on device).");
                    pw.println("            -v: lists more verbose information about each app.");
                    pw.println("            -l: lists shared library info. This will only be "
                               + "listed with -v");
                    pw.println("");
                }

                @Override
                public void onHelp() {
                    printHelpMenu();
                }
            }).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
    private final BinaryTransparencyServiceImpl mServiceImpl;

    public BinaryTransparencyService(Context context) {
        super(context);
        mContext = context;
        mServiceImpl = new BinaryTransparencyServiceImpl();
        mVbmetaDigest = VBMETA_DIGEST_UNINITIALIZED;
        mMeasurementsLastRecordedMs = 0;
    }

    /**
     * Called when the system service should publish a binder service using
     * {@link #publishBinderService(String, IBinder).}
     */
    @Override
    public void onStart() {
        try {
            publishBinderService(Context.BINARY_TRANSPARENCY_SERVICE, mServiceImpl);
            Slog.i(TAG, "Started BinaryTransparencyService");
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to start BinaryTransparencyService.", t);
        }
    }

    /**
     * Called on each phase of the boot process. Phases before the service's start phase
     * (as defined in the @Service annotation) are never received.
     *
     * @param phase The current boot phase.
     */
    @Override
    public void onBootPhase(int phase) {

        // we are only interested in doing things at PHASE_BOOT_COMPLETED
        if (phase == PHASE_BOOT_COMPLETED) {
            Slog.i(TAG, "Boot completed. Getting VBMeta Digest.");
            getVBMetaDigestInformation();

            // to avoid the risk of holding up boot time, computations to measure APEX, Module, and
            // MBA digests are scheduled here, but only executed when the device is idle and plugged
            // in.
            Slog.i(TAG, "Scheduling measurements to be taken.");
            UpdateMeasurementsJobService.scheduleBinaryMeasurements(mContext,
                    BinaryTransparencyService.this);
        }
    }

    /**
     * JobService to measure all covered binaries and record result to Westworld.
     */
    public static class UpdateMeasurementsJobService extends JobService {
        private static final int DO_BINARY_MEASUREMENTS_JOB_ID =
                UpdateMeasurementsJobService.class.hashCode();

        @Override
        public boolean onStartJob(JobParameters params) {
            Slog.d(TAG, "Job to update binary measurements started.");
            if (params.getJobId() != DO_BINARY_MEASUREMENTS_JOB_ID) {
                return false;
            }

            // we'll perform binary measurements via threads to be mindful of low-end devices
            // where this operation might take longer than expected, and so that we don't block
            // system_server's main thread.
            Executors.defaultThreadFactory().newThread(() -> {
                // we discard the return value of getMeasurementsForAllPackages() as the
                // results of the measurements will be recorded, and that is what we're aiming
                // for with this job.
                IBinder b = ServiceManager.getService(Context.BINARY_TRANSPARENCY_SERVICE);
                IBinaryTransparencyService iBtsService =
                        IBinaryTransparencyService.Stub.asInterface(b);
                try {
                    iBtsService.getMeasurementsForAllPackages();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Taking binary measurements was interrupted.", e);
                    return;
                }
                jobFinished(params, false);
            }).start();

            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }

        @SuppressLint("DefaultLocale")
        static void scheduleBinaryMeasurements(Context context, BinaryTransparencyService service) {
            Slog.i(TAG, "Scheduling binary content-digest computation job");
            final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
            if (jobScheduler == null) {
                Slog.e(TAG, "Failed to obtain an instance of JobScheduler.");
                return;
            }

            final JobInfo jobInfo = new JobInfo.Builder(DO_BINARY_MEASUREMENTS_JOB_ID,
                    new ComponentName(context, UpdateMeasurementsJobService.class))
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setPeriodic(RECORD_MEASUREMENTS_COOLDOWN_MS)
                    .build();
            if (jobScheduler.schedule(jobInfo) != JobScheduler.RESULT_SUCCESS) {
                Slog.e(TAG, "Failed to schedule job to measure binaries.");
                return;
            }
            Slog.d(TAG, TextUtils.formatSimple(
                    "Job %d to measure binaries was scheduled successfully.",
                    DO_BINARY_MEASUREMENTS_JOB_ID));
        }
    }

    private void getVBMetaDigestInformation() {
        mVbmetaDigest = SystemProperties.get(SYSPROP_NAME_VBETA_DIGEST, VBMETA_DIGEST_UNAVAILABLE);
        Slog.d(TAG, String.format("VBMeta Digest: %s", mVbmetaDigest));
        FrameworkStatsLog.write(FrameworkStatsLog.VBMETA_DIGEST_REPORTED, mVbmetaDigest);
    }

    @NonNull
    private List<PackageInfo> getCurrentInstalledApexs() {
        List<PackageInfo> results = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Error obtaining an instance of PackageManager.");
            return results;
        }
        List<PackageInfo> allPackages = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX
                        | PackageManager.GET_SIGNING_CERTIFICATES));
        if (allPackages == null) {
            Slog.e(TAG, "Error obtaining installed packages (including APEX)");
            return results;
        }

        results = allPackages.stream().filter(p -> p.isApex).collect(Collectors.toList());
        return results;
    }

    @Nullable
    private InstallSourceInfo getInstallSourceInfo(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Error obtaining an instance of PackageManager.");
            return null;
        }
        try {
            return pm.getInstallSourceInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    @NonNull
    private String getOriginalApexPreinstalledLocation(String packageName,
                                                   String currentInstalledLocation) {
        // get a listing of all apex files in /system/apex/
        Set<String> originalApexs = Stream.of(new File(APEX_PRELOAD_LOCATION).listFiles())
                                        .filter(f -> !f.isDirectory())
                                        .map(File::getName)
                                        .collect(Collectors.toSet());

        for (String originalApex : originalApexs) {
            if (originalApex.startsWith(packageName)) {
                return APEX_PRELOAD_LOCATION + originalApex;
            }
        }

        return APEX_PRELOAD_LOCATION_ERROR;
    }

    /**
     * Wrapper method to call into IBICS to get a list of all newly installed MBAs.
     *
     * We expect IBICS to maintain an accurate list of installed MBAs, and we merely make use of
     * the results within this service. This means we do not further check whether the
     * apps in the returned slice is still installed or not, esp. considering that preloaded apps
     * could be updated, or post-setup installed apps *might* be deleted in real time.
     *
     * Note that we do *not* cache the results from IBICS because of the more dynamic nature of
     * MBAs v.s. other binaries that we measure.
     *
     * @return a list of preloaded apps + dynamically installed apps that fit the definition of MBA.
     */
    @NonNull
    private List<PackageInfo> getNewlyInstalledMbas() {
        List<PackageInfo> result = new ArrayList<>();
        IBackgroundInstallControlService iBics = IBackgroundInstallControlService.Stub.asInterface(
                ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        if (iBics == null) {
            Slog.e(TAG,
                    "Failed to obtain an IBinder instance of IBackgroundInstallControlService");
            return result;
        }
        ParceledListSlice<PackageInfo> slice;
        try {
            slice = iBics.getBackgroundInstalledPackages(
                    PackageManager.MATCH_ALL | PackageManager.GET_SIGNING_CERTIFICATES,
                    UserHandle.USER_SYSTEM);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get a list of MBAs.", e);
            return result;
        }
        return slice.getList();
    }
}
