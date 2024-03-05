package me.bmax.apatch.util

import android.content.Context
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import dev.utils.app.AppUtils.getPackageManager
import dev.utils.app.AppUtils.getPackageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig.APPLICATION_ID
import me.bmax.apatch.R
import me.bmax.apatch.util.apksign.JarMap
import me.bmax.apatch.util.apksign.SignApk
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom

object HideAPK {
    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private const val ALPHADOTS = "$ALPHA....."
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"

    @JvmStatic
    private fun genPackageName(): String {
        val random = SecureRandom()
        val len = 5 + random.nextInt(15)
        val builder = StringBuilder(len)
        var next: Char
        var prev = 0.toChar()
        for (i in 0 until len) {
            next = if (prev == '.' || i == 0 || i == len - 1) {
                ALPHA[random.nextInt(ALPHA.length)]
            } else {
                ALPHADOTS[random.nextInt(ALPHADOTS.length)]
            }
            builder.append(next)
            prev = next
        }
        if (!builder.contains('.')) {
            // Pick a random index and set it as dot
            val idx = random.nextInt(len - 2)
            builder[idx + 1] = '.'
        }
        return builder.toString()
    }

    @JvmStatic
    private fun patch(
        apk: File, out: OutputStream,
        pkg: String, label: CharSequence
    ): Boolean {
        val origLabel = "APatch" // TODO: Get this in a better way instead of hardcode
        try {
            JarMap.open(apk, true).use { jar ->
                val je = jar.getJarEntry(ANDROID_MANIFEST)
                val xml = AXML(jar.getRawData(je))

                if (!xml.patchStrings {
                    for (i in it.indices) {
                        val s = it[i]
                        if (s.contains(APPLICATION_ID) && !s.contains("ui.MainActivity") && !s.contains(".APApplication")) {
                            it[i] = s.replace(APPLICATION_ID, pkg)
                        } else if (s == origLabel) {
                            it[i] = label.toString()
                        }
                    }
                }) {
                    return false
                }

                // Write apk changes
                jar.getOutputStream(je).use { it.write(xml.bytes) }
                val keys = Keygen()
                SignApk.sign(keys.cert, keys.key, jar, out)
                return true
            }
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
    }

    @JvmStatic
    private fun patchAndHide(context: Context, label: String, shell: Shell): Boolean {
        val apkPath: String = getPackageManager().getApplicationInfo(getPackageName(), 0).sourceDir
        val source = File(apkPath)

        // Generate a new random package name and signature
        val repack = File(context.cacheDir, "patched.apk")
        val pkg = genPackageName()

        if (!patch(source, FileOutputStream(repack), pkg, label))
            return false

        // https://github.com/topjohnwu/Magisk/blob/master/app/src/main/res/raw/manager.sh#L135
        val result = shellForResult(shell,
            "adb_pm_install() { local tmp=/data/local/tmp/temp.apk; cp -f \"\$1\" \$tmp; chmod 644 \$tmp; su 2000 -c \"pm install -g \$tmp\" || pm install -g \$tmp || su 1000 -c \"pm install -g \$tmp\"; local res=\$?; rm -f \$tmp; if [ \$res = 0 ]; then appops set \"\$2\" REQUEST_INSTALL_PACKAGES allow; fi; return \$res; }\n",
            "adb_pm_install $repack $pkg",
            "am start -n $pkg/$APPLICATION_ID.ui.MainActivity",
            "pm uninstall $APPLICATION_ID",
        )

        return result.isSuccess
    }

    @Suppress("DEPRECATION")
    suspend fun hide(context: Context, label: String) {
        val dialog = android.app.ProgressDialog(context).apply {
            setTitle(context.getString(R.string.hide_apatch_manager))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        val onFailure = Runnable {
            dialog.dismiss()
            Toast.makeText(context, context.getString(R.string.hide_apatch_manager_failure), Toast.LENGTH_LONG).show()
        }
        val shell = tryGetRootShell()
        if (!shell.isRoot) {
            onFailure.run()
        }
        val success = withContext(Dispatchers.IO) { patchAndHide(context, label, shell)}
        if (!success) onFailure.run()
    }
}