package dev.folio.mobile.core.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes

/**
 * Google account selection and Drive credential plumbing for cloud sync.
 *
 * The app never sees the user's Google password: it asks the system account
 * manager for a token bound to the narrow [DriveScopes.DRIVE_FILE] scope.
 * `drive.file` grants per-file access to the visible "Folio Library" folder and
 * the files Folio creates there; it is not full Drive access.
 *
 * The selected account name is persisted so the user only picks once; the
 * token itself stays in the system credential store and is refreshed
 * automatically.
 *
 * Account visibility on Android O+ is user-mediated: listing accounts is not a
 * reliable way to offer a picker, so selection always goes through
 * [AccountManager.newChooseAccountIntent]. Direct discovery is only used to
 * notice that a previously selected account has since been removed.
 */
class DriveAuth(private val context: Context) {

    companion object {
        private const val TAG = "FolioDriveAuth"
        private const val PREFS = "folio-drive-auth"
        private const val KEY_ACCOUNT = "account-name"
        private const val KEY_ACCOUNT_TYPE = "account-type"

        /**
         * The only scope Folio ever asks for.
         *
         * `drive.file` — per-file access to the files Folio creates in its
         * visible "Folio Library" folder and nothing else in the user's Drive.
         * It is a sensitive scope, but not a restricted one, so it needs no
         * security assessment.
         */
        val SCOPES: List<String> = listOf(DriveScopes.DRIVE_FILE)

        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }

    /**
     * Result of checking the persisted account. This deliberately keeps
     * "removed" separate from "could not check": Android may hide accounts
     * from a target-O app until the user picks one through the system UI.
     */
    sealed interface SavedAccountState {
        data object NotConnected : SavedAccountState
        data class Connected(val name: String) : SavedAccountState
        data class Missing(val name: String) : SavedAccountState
        data class Unverified(val name: String) : SavedAccountState
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The supported, user-mediated account chooser. It returns the chosen
     * account name/type through activity result extras, and also offers the
     * system "Add account" route when none exists. Requires GET_ACCOUNTS.
     */
    fun chooseAccountIntent(): Intent = AccountManager.newChooseAccountIntent(
        null,
        null,
        arrayOf(GOOGLE_ACCOUNT_TYPE),
        "Folio uses this account only to mirror your library to Google Drive.",
        null,
        null,
        null,
    )

    /**
     * Checks the saved account without treating an API/permission failure as
     * "no accounts". Only [SavedAccountState.Missing] means the account is
     * genuinely absent from the visible account list.
     */
    fun savedAccountState(): SavedAccountState {
        val name = selectedAccountName() ?: return SavedAccountState.NotConnected
        return try {
            val visible = AccountManager.get(context)
                .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
            if (visible.any { it.name == name }) {
                SavedAccountState.Connected(name)
            } else {
                SavedAccountState.Missing(name)
            }
        } catch (e: SecurityException) {
            // Account visibility can be restricted even with GET_ACCOUNTS.
            // Do not tell the user their account disappeared.
            Log.w(TAG, "Google account visibility unavailable: ${e.javaClass.simpleName}")
            SavedAccountState.Unverified(name)
        } catch (e: Exception) {
            Log.w(TAG, "Google account query failed: ${e.javaClass.simpleName}")
            SavedAccountState.Unverified(name)
        }
    }

    /** Name of the account currently connected, or null. */
    fun selectedAccountName(): String? = prefs.getString(KEY_ACCOUNT, null)

    /** True when an account has been selected (not proof of Drive consent). */
    fun isConnected(): Boolean = selectedAccountName() != null

    /**
     * Remembers the account the user picked.
     *
     * @return true when this replaced a different previously saved account;
     * callers should discard the old account's local sync-state mapping.
     */
    fun connect(account: Account): Boolean {
        val previous = selectedAccountName()
        prefs.edit()
            .putString(KEY_ACCOUNT, account.name)
            .putString(KEY_ACCOUNT_TYPE, account.type)
            .apply()
        return previous != null && previous != account.name
    }

    /** Forgets the connected account (the cloud snapshot itself is untouched). */
    fun disconnect() {
        prefs.edit().remove(KEY_ACCOUNT).remove(KEY_ACCOUNT_TYPE).apply()
    }

    /**
     * A credential for the connected account, or null when no account has been
     * selected yet. The credential fetches and refreshes tokens on demand.
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
     * Fallback only: opens the system "add account" screen. The chooser from
     * [chooseAccountIntent] normally includes this action itself, so this is
     * used when the chooser cannot be launched at all.
     */
    fun addAccountIntent(): Intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT_TYPE))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
