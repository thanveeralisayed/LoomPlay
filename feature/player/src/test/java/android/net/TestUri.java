package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public final class TestUri extends Uri {
    private final String raw;

    public TestUri(String raw) {
        this.raw = raw;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        int idx = raw.indexOf("://");
        return idx > 0 ? raw.substring(0, idx) : null;
    }

    @Override
    public String getSchemeSpecificPart() {
        return raw;
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return raw;
    }

    @Override
    public String getAuthority() {
        return null;
    }

    @Override
    public String getEncodedAuthority() {
        return null;
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return raw.substring(raw.indexOf("://") + 3);
    }

    @Override
    public String getEncodedPath() {
        return getPath();
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        int slash = raw.lastIndexOf('/');
        return slash >= 0 && slash + 1 < raw.length() ? raw.substring(slash + 1) : raw;
    }

    @Override
    public Builder buildUpon() {
        return null;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}

