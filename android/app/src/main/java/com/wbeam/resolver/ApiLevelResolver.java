package com.wbeam.resolver;

import android.os.Build;

import com.wbeam.compat.Api17CompatPolicy;
import com.wbeam.compat.Api21CompatPolicy;
import com.wbeam.compat.Api29CompatPolicy;
import com.wbeam.compat.ApiCompatPolicy;

public final class ApiLevelResolver {
    private ApiLevelResolver() {}

    public static ApiCompatPolicy resolve(int sdkInt) {
        if (sdkInt <= 17) {
            return new Api17CompatPolicy();
        }
        if (sdkInt >= 29) {
            return new Api29CompatPolicy();
        }
        return new Api21CompatPolicy();
    }

    public static ApiCompatPolicy resolveCurrentDevice() {
        return resolve(Build.VERSION.SDK_INT);
    }
}
