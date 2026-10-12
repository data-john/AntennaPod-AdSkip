package de.danoeh.antennapod.ui.adskip;

import android.content.Context;
import android.text.InputType;
import android.widget.Toast;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class AdSkipSettings {
    private AdSkipSettings() {
    }

    public static void addTo(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(R.string.ad_skip_title);
        fragment.getPreferenceScreen().addPreference(category);
        addSwitch(category, AdSkipPreferences.ENABLED, R.string.ad_skip_enabled, R.string.ad_skip_enabled_summary);
        EditTextPreference origin = new EditTextPreference(context);
        origin.setKey(AdSkipPreferences.ORIGIN);
        origin.setTitle(R.string.ad_skip_origin);
        origin.setDefaultValue(AdSkipPreferences.origin(context));
        origin.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
        origin.setOnPreferenceChangeListener((preference, value) -> {
            try {
                String address = AdSkipPreferences.validateOrigin((String) value);
                origin.setText(address);
                AdSkipPreferences.changeGeneration(context);
            } catch (IllegalArgumentException e) {
                Toast.makeText(context, R.string.ad_skip_invalid_origin, Toast.LENGTH_LONG).show();
            }
            return false;
        });
        category.addPreference(origin);
        EditTextPreference token = new EditTextPreference(context);
        token.setKey("adSkipToken");
        token.setPersistent(false);
        token.setTitle(R.string.ad_skip_token);
        token.setSummary(R.string.ad_skip_token_summary);
        token.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editText.setText("");
        });
        token.setOnPreferenceChangeListener((preference, value) -> {
            Completable.fromAction(() -> AdSkipPreferences.setToken(context.getApplicationContext(), (String) value))
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .subscribe(() -> Toast.makeText(context, R.string.ad_skip_token_saved, Toast.LENGTH_SHORT).show(),
                            error -> Toast.makeText(context, R.string.ad_skip_credentials, Toast.LENGTH_LONG).show());
            return false;
        });
        category.addPreference(token);
        addSwitch(category, AdSkipPreferences.AUTO_SKIP, R.string.ad_skip_automatic, R.string.ad_skip_local_summary);
        addSwitch(category, AdSkipPreferences.MOBILE_DATA, R.string.ad_skip_mobile, R.string.ad_skip_mobile_summary);
        SwitchPreferenceCompat queueStatus = new SwitchPreferenceCompat(context);
        queueStatus.setKey(AdSkipPreferences.SHOW_QUEUE_STATUS);
        queueStatus.setTitle(R.string.ad_skip_show_queue_status);
        queueStatus.setSummary(R.string.ad_skip_show_queue_status_summary);
        queueStatus.setDefaultValue(true);
        category.addPreference(queueStatus);
    }

    private static void addSwitch(PreferenceCategory category, String key, int title, int summary) {
        SwitchPreferenceCompat preference = new SwitchPreferenceCompat(category.getContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setDefaultValue(false);
        category.addPreference(preference);
    }
}
