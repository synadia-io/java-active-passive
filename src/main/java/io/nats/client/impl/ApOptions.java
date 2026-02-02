package io.nats.client.impl;

import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Options;

public class ApOptions {

    public final Options options;
    public final ConnectionListener passiveConnectionListener;
    public final ErrorListener passiveErrorListener;

    public ApOptions(Builder b) {
        this.options = b.options;
        this.passiveConnectionListener = b.passiveConnectionListener;
        this.passiveErrorListener = b.passiveErrorListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Options options) {
        return new Builder().options(options);
    }

    public static class Builder {
        Options options;
        ConnectionListener passiveConnectionListener;
        ErrorListener passiveErrorListener;

        public Builder() {}

        public Builder(ApOptions ap) {
            if (ap != null) {
                this.options = new Options.Builder(ap.options).build();
                this.passiveConnectionListener = ap.passiveConnectionListener;
                this.passiveErrorListener = ap.passiveErrorListener;
            }
        }

        public Builder options(Options options) {
            this.options = options;
            return this;
        }

        public Builder passiveConnectionListener(ConnectionListener passiveConnectionListener) {
            this.passiveConnectionListener = passiveConnectionListener;
            return this;
        }

        public Builder passiveErrorListener(ErrorListener passiveErrorListener) {
            this.passiveErrorListener = passiveErrorListener;
            return this;
        }

        public ApOptions build() {
            if (options == null) {
                options = new Options.Builder().build();
            }
            return new ApOptions(this);
        }
    }
}
