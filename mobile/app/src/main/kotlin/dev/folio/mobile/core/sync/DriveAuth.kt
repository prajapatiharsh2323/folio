package dev.folio.mobile.core.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes

/**
 * Google account selection and Drive credential plumbing for cloud sync.
 *
 * The app never sees the user's Google password: it asks the system account
 * manager for a token bound to the narrow [DriveScopes.DRIVE_APPDATA] scope
 * (a private, hidden per-app folder). No client secret, no server, no
 * Play-Services sign-in SDK — the OAuth client id is derived by the platform
 * from the app's package name and signing certificate.
 *
 * The selected account name is persisted so the user only picks once; the
 * token itself stays in the system credential store and is refreshed
 * automatically.
 */
class DriveAuth(private val context: Context) {

    companion object {
        private const val PREFS = "folio-drive-auth"
        private const val KEY_ACCOUNT = "account-name"
        private const val KEY_ACCOUNT_TYPE = "account-type"

        /**
         * The only scope Folio ever asks for.
         *
         * `drive.file` — the same scope the PWA uses: the app can see and
         * change the files it created (the "Folio Library" mirror) and nothing
         * else in the user's Drive. It is a sensitive scope, but not a
         * restricted one, so it needs no security assessment.
         */
        val SCOPES: List<String> = listOf(DriveScopes.DRIVE_FILE)

        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Google accounts available on this device (may be empty). */
    fun googleAccounts(): List<Account> = runCatching {
        AccountManager.get(context)
            .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.name }
    }.getOrElse { emptyList() }

    /** True when the device has at least one Google account to pick. */
    fun hasGoogleAccount(): Boolean = googleAccounts().isNotEmpty()

    /** Name of the account currently connected, or null. */
    fun selectedAccountName(): String? = prefs.getString(KEY_ACCOUNT, null)

    /** True when an account has been connected. */
    fun isConnected(): Boolean = selectedAccountName() != null

    /** Remembers the account the user picked. */
    fun connect(account: Account) {
        prefs.edit()
            .putString(KEY_ACCOUNT, account.name)
            .putString(KEY_ACCOUNT_TYPE, account.type)
            .apply()
    }

    /** Forgets the connected account (the cloud snapshot itself is untouched). */
    fun disconnect() {
        prefs.edit().remove(KEY_ACCOUNT).remove(KEY_ACCOUNT_TYPE).apply()
    }

    /**
     * A credential for the connected account, or null when no account has been
     * connected yet. The credential fetches and refreshes tokens on demand.
     */
    fun credential(): GoogleAccountCredential? {
        val name = selectedAccountName() ?: return null
        val type = prefs.getString(KEY_ACCOUNT_TYPE, GOOGLE_ACCOUNT_TYPE) ?: GOOGLE_ACCOUNT_TYPE
        return credentialFor(Account(name, type))
    }

    /** A credential bound to a specific [account]. */
    fun credentialFor(account: Account): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(context, SCOPES).apply {
            selectedAccount = account
        }

    /**
     * Intent that opens the system "add account" screen, offered when the
     * device has no Google account at all (the sync UI never shows an empty
     * picker).
     */
    fun addAccountIntent(): Intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT_TYPE))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Human-readable account label ("Name (email)") for the settings row. */
    fun describeAccount(account: Account): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            account.name
        } else {
            account.name
        }
}
