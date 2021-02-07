package mirror;

public enum SyncDirection {
    INBOUND {
        @Override
        public boolean getAllowInbound() {
            return true;
        }

        @Override
        public boolean getAllowOutbound() {
            return false;
        }

        @Override
        public SyncDirection getComplement() {
            return OUTBOUND;
        }
    },
    OUTBOUND {
        @Override
        public boolean getAllowInbound() {
            return false;
        }

        @Override
        public boolean getAllowOutbound() {
            return true;
        }

        @Override
        public SyncDirection getComplement() {
            return INBOUND;
        }
    },
    BOTH {
        @Override
        public boolean getAllowInbound() {
            return true;
        }

        @Override
        public boolean getAllowOutbound() {
            return true;
        }

        @Override
        public SyncDirection getComplement() {
            return BOTH;
        }
    };


    public abstract boolean getAllowInbound();

    public abstract boolean getAllowOutbound();

    public abstract SyncDirection getComplement();
}
