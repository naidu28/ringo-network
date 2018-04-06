import java.io.PrintStream;
import java.lang.Thread;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.function.Function;

/**
 * This class instantiates the whole Ringo application. Given the correct number
 * and type of command-line arguments, this class starts a Ringo node as a child
 * Thread
 * 
 * @author sainaidu
 */
public class App {
	public static final int NUM_ARGS = 5;
	public static final int MAX_PORT = 65535;

	private static Role role;
	private static int port;
	private static String pocHost;
	private static int pocPort;
	private static int n;
	
	private static DatagramSocket socket;

	public static void main(String[] args) {
		try {
			parseArguments(args);
		} catch (IllegalArgumentException e) {
			printHelp(System.err);
			System.out.println(e.getMessage());
			System.exit(1);
		}

		System.out.println(String.format("Provided Arguments: Role: %s\tLocal Port: %d\tPoC: %s:%d\tN: %d\n",
				role.toString(), port, pocHost, pocPort, n));

		try {
			socket = new DatagramSocket(port);
		} catch(SocketException e) {
			String errmsg = String.format("Could not bind to port %d. Exiting (1)\n%s", port, e.getMessage());
			System.err.println(errmsg);
			System.exit(1);
		}

		Thread ringoThread = new Thread(new Ringo(role, port, pocHost, pocPort, n, socket));
		ringoThread.start();
		while (ringoThread.isAlive()) {}

		System.exit(0);
	}

	private static void parseArguments(String[] args) throws IllegalArgumentException {
		if (args.length < NUM_ARGS) {
			printHelp(System.err);
			throw new IllegalArgumentException("At least four arguments must be given");
		}

		ArgumentChecker<String, Integer> intchecker = new ArgumentChecker<>((String arg) -> Integer.parseInt(arg));
		ArgumentChecker<String, String> hostchecker = new ArgumentChecker<>((String arg) -> (arg.equals("0") ? null : arg));

		role = Role.fromString(args[0]);
		port = intchecker.check(args[1], "Given Local Port isn't valid", 
			(Integer i) -> (i > 0) && (i <= MAX_PORT)
		);
		pocHost = hostchecker.check(args[2], "Given POC Host isn't valid", (String s) -> new Boolean(true));
		pocPort = intchecker.check(args[3], "Provided POC Port isn't valid", 
			(Integer i) -> 
				((pocHost == null && i == 0) || (pocHost != null && i > 0))
				&& (i >= 0)
				&& (i <= MAX_PORT)
		);
		n = intchecker.check(args[4], "Given N isn't valid", (Integer i) -> i > 0);
	}

	private static void printHelp(PrintStream stream) {
		stream.println("Runtime Arguments:");
		stream.println("java Ringo <Role> <Local Port> <PoC Hostname> <PoC Port> <N>");
		stream.println("- Role: Sets this Ringo to be either a (S)ender, (R)eceiver, or a (F)orwarder");
		stream.println("- PoC Hostname: The Hostname of the Point of Contact. Set to 0 if no PoC");
		stream.println("- PoC Port: Port to use while contacting the PoC. Set to 0 if no PoC");
		stream.println("- N: Number of Ringos in the mesh");
		stream.println("");
	}
}

class ArgumentChecker<T, R> {
	
	Function<T, R> mutator;
	
	public ArgumentChecker(Function<T, R> mutator) {
		this.mutator = mutator;
	}
	
	public R check(T arg, String errmsg, Function<R, Boolean> checker) throws IllegalArgumentException {
		try {
			R converted = mutator.apply(arg);
			boolean sane = checker.apply(converted);
			if (!sane)
				throw new IllegalArgumentException();
			return converted;
		} catch (Throwable e) {
			String extraInfo = (e.getMessage() != null) ? ": " + e.getMessage() : "";
			throw new IllegalArgumentException(errmsg + extraInfo);
		}
	}
}
