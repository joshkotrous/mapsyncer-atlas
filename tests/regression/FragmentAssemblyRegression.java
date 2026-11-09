import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.MapFragmentAssembler;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class FragmentAssemblyRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static ChunkMapData part(int region, int index, int total, long timestamp, byte... bytes) {
        return new ChunkMapData(region, 0, "minecraft:overworld", bytes, timestamp,
                Integer.MAX_VALUE, index, total);
    }
    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(1000);
        MapFragmentAssembler a = new MapFragmentAssembler(8, 8, 2, 12, now::get);
        check(a.accept(part(0, 0, Integer.MAX_VALUE, 1)) == null, "huge count rejected before allocation");
        check(a.pendingRegions() == 0, "huge count retains nothing");
        for (int total : new int[]{-1, 0, 1, 2, 8, 9, Integer.MAX_VALUE}) {
            for (int index : new int[]{-1, 0, 1, 7, 8, Integer.MAX_VALUE}) {
                a.clear();
                a.accept(part(0, index, total, 1, (byte) 3));
                check(a.pendingBytes() <= 12 && a.pendingRegions() <= 2, "invalid metadata remains bounded");
            }
        }
        a.clear();
        check(a.accept(part(0, 0, 2, 1, (byte) 1)) == null, "first fragment waits");
        check(a.accept(part(0, 2, 3, 1, (byte) 2)) == null, "changing count rejected without array overrun");
        check(a.pendingBytes() == 0 && a.pendingRegions() == 0, "mismatched group discarded");
        a.accept(part(0, 0, 2, 1, (byte) 1));
        a.accept(part(0, 1, 2, 2, (byte) 2));
        check(a.pendingBytes() == 0, "mixed timestamps rejected");
        a.accept(part(0, 1, 2, 1, (byte) 3, (byte) 4));
        a.accept(part(0, 1, 2, 1, (byte) 3, (byte) 4));
        check(a.pendingBytes() == 2, "duplicate does not count twice");
        ChunkMapData result = a.accept(part(0, 0, 2, 1, (byte) 1, (byte) 2));
        check(result != null && Arrays.equals(result.data, new byte[]{1,2,3,4}), "out-of-order reconstruction");
        check(a.pendingBytes() == 0 && a.pendingRegions() == 0, "completion releases accounting");
        a.accept(part(0, 0, 2, 1, (byte) 1));
        a.accept(part(0, 0, 2, 1, (byte) 9));
        check(a.pendingRegions() == 0, "conflicting duplicate discarded");
        a.accept(part(0, 0, 2, 1, new byte[5]));
        a.accept(part(0, 1, 2, 1, new byte[4]));
        check(a.pendingBytes() == 0, "per-region byte cap enforced");
        a.accept(part(0, 0, 2, 1, new byte[7]));
        a.accept(part(1, 0, 2, 1, new byte[6]));
        check(a.pendingBytes() == 7 && a.pendingRegions() == 1, "global byte cap enforced");
        a.accept(part(1, 0, 2, 1));
        a.accept(part(2, 0, 2, 1));
        check(a.pendingRegions() == 2, "global region count cap enforced");
        now.addAndGet(120001);
        a.cleanStale();
        check(a.pendingBytes() == 0 && a.pendingRegions() == 0, "stale assemblies expire");
        a.accept(part(0, 0, 2, 1, (byte) 5));
        now.set(0);
        a.cleanStale();
        check(a.pendingRegions() == 0, "backward clock movement cannot keep groups indefinitely");
        ChunkMapData plain = part(0, 0, 0, 1, (byte) 8);
        check(a.accept(plain) == plain, "legacy unsplit packet accepted");
        check(a.accept(part(0, 0, 0, 1, new byte[9])) == null, "oversized unsplit packet rejected");
        check(a.accept(part(0, 0, 2, 1, new byte[28001])) == null, "oversized fragment rejected");
        a.clear();
        check(a.pendingBytes() == 0, "disconnect cleanup releases retained data");
        System.out.println("FragmentAssemblyRegression passed " + checks + " checks");
    }
}
