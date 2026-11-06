/* -*- Mode: Java; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*- */
/*
 * This file is part of the LibreOffice project.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.libreoffice.kit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.IOException;

// Native methods in this class are all implemented in
// sal/android/lo-bootstrap.c as the lo-bootstrap library is loaded with
// System.loadLibrary() and Android's JNI works only to such libraries, it
// seems.
public final class LibreOfficeKit
{
    private static String LOGTAG = LibreOfficeKit.class.getSimpleName();
    private static AssetManager mgr;

    // private constructor because instantiating would be meaningless
    private LibreOfficeKit() {
    }

    // Trigger library initialization - as this is done automatically by executing the "static" block, this method remains empty. However we need this to manually (at the right time) can force library initialization.
    public static void initializeLibrary() {
        NativeLibLoader.load();
    }

    // IKO modification: only a verified private, pinned arm64 resource pack may supply libraries.
    public static void configureLibraryDirectory(File directory) throws IOException {
        NativeLibLoader.configure(directory);
    }

    // Trigger initialization on the JNI - LOKit side.
    private static native boolean initializeNative(String dataDir, String cacheDir, String apkFile, AssetManager mgr);

    public static native ByteBuffer getLibreOfficeKitHandle();

    // Wrapper for putenv()
    public static native void putenv(String string);

    // A method that starts a thread to redirect stdout and stderr writes to
    // the Android logging mechanism, or stops the redirection.
    public static native void redirectStdio(boolean state);

    static boolean initializeDone = false;

    // This init() method should be called from the upper Java level of
    // LO-based apps.
    public static synchronized void init(Context activity, String dataDir)
    {
        if (initializeDone) {
            return;
        }

        mgr = activity.getResources().getAssets();

        ApplicationInfo applicationInfo = activity.getApplicationInfo();
        // IKO modification: use an engine-specific data directory.
        Log.i(LOGTAG, String.format("Initializing LibreOfficeKit, dataDir=%s\n", dataDir));

        redirectStdio(true);

        String cacheDir = activity.getCacheDir().getAbsolutePath();
        String apkFile = activity.getPackageResourcePath();

        if (!initializeNative(dataDir, cacheDir, apkFile, mgr)) {
            Log.e(LOGTAG, "Initialize native failed!");
            return;
        }
        initializeDone = true;
    }

    // Now with static loading we always have all native code in one native
    // library which we always call liblo-native-code.so, regardless of the
    // app. The library has already been unpacked into /data/data/<app
    // name>/lib at installation time by the package manager.
}

class NativeLibLoader {
        private static boolean done = false;
        private static File directory;

        protected static synchronized void configure(File value) throws IOException {
            File canonical=value.getCanonicalFile();
            if(!canonical.equals(value.getAbsoluteFile()) || !canonical.isDirectory())
                throw new IOException("文档引擎库目录无效");
            if(done && !canonical.equals(directory))throw new IOException("文档引擎正在使用其他版本");
            directory=canonical;
        }

        protected static synchronized void load() {
            if (done)
                return;
            if(directory==null)throw new IllegalStateException("先校验并配置文档引擎资源包");
            String[] names={"nspr4","plds4","plc4","nssutil3","freebl3","sqlite3","softokn3","nss3","nssckbi","nssdbm3","smime3","ssl3","c++_shared","lo-native-code"};
            for(String name:names) {
                File library=new File(directory,"lib"+name+".so");
                try {
                    if(!library.getAbsoluteFile().equals(library.getCanonicalFile()) || !library.isFile() || library.canWrite())
                        throw new IOException("文档引擎库未经只读保护："+name);
                } catch(IOException e){throw new IllegalStateException(e);}
                System.load(library.getAbsolutePath());
            }
            done = true;
        }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
