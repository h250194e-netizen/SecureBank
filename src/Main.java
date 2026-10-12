import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Main {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{3,20}");
    private final BufferedReader input = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final Console console = System.console();
    private final BankStore store;

    private Main(Path dataFile) throws IOException {
        store = new BankStore(dataFile);
        store.load();
    }

    public static void main(String[] args) {
        Path dataFile = args.length > 0 ? Path.of(args[0]) : Path.of("data", "bank-data.txt");
        try {
            new Main(dataFile).run();
        } catch (IOException exception) {
            System.err.println("Bank data could not be read or saved: " + exception.getMessage());
        }
    }

    private void run() throws IOException {
        System.out.println("=== SecureBank Console ===");
        while (true) {
            System.out.println("\n1. Log in\n2. Create user\n3. Exit");
            String choice = prompt("Choose an option: ");
            if (choice == null || choice.equals("3")) {
                System.out.println("Goodbye.");
                return;
            }
            if (choice.equals("1")) {
                User user = login();
                if (user != null) {
                    userMenu(user);
                }
            } else if (choice.equals("2")) {
                User user = register();
                if (user != null) {
                    userMenu(user);
                }
            } else {
                System.out.println("Choose 1, 2, or 3.");
            }
        }
    }

    private User register() throws IOException {
        String username = prompt("New username (3-20 letters, numbers, ., _, or -): ");
        if (username == null) {
            return null;
        }
        username = username.toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            System.out.println("That username does not meet the required format.");
            return null;
        }
        if (store.users.containsKey(username)) {
            System.out.println("That username is already registered.");
            return null;
        }

        char[] password = readPassword("Password (at least 10 characters): ");
        if (password == null) {
            return null;
        }
        char[] confirmation = readPassword("Confirm password: ");
        if (confirmation == null) {
            Arrays.fill(password, '\0');
            return null;
        }
        try {
            if (password.length < 10) {
                System.out.println("Choose a password with at least 10 characters.");
                return null;
            }
            if (!Arrays.equals(password, confirmation)) {
                System.out.println("The passwords do not match.");
                return null;
            }
            User user = PasswordSecurity.createUser(username, password);
            store.users.put(username, user);
            store.save();
            System.out.println("User created. You are now logged in.");
            return user;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Password protection is unavailable.", exception);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private User login() throws IOException {
        String username = prompt("Username: ");
        if (username == null) {
            return null;
        }
        username = username.toLowerCase(Locale.ROOT);
        char[] password = readPassword("Password: ");
        if (password == null) {
            return null;
        }
        try {
            User user = store.users.get(username);
            boolean authenticated = user == null
                    ? PasswordSecurity.consumeDummyWork(password)
                    : PasswordSecurity.verify(user, password);
            if (!authenticated) {
                System.out.println("Invalid username or password.");
                return null;
            }
            System.out.println("Welcome, " + user.username + ".");
            return user;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Password verification is unavailable.", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void userMenu(User user) throws IOException {
        while (true) {
            System.out.println("\n1. View accounts and balances"
                    + "\n2. Open account"
                    + "\n3. Deposit"
                    + "\n4. Withdraw"
                    + "\n5. Transaction history"
                    + "\n6. Log out");
            String choice = prompt("Choose an option: ");
            if (choice == null || choice.equals("6")) {
                return;
            }
            switch (choice) {
                case "1":
                    showAccounts(user);
                    break;
                case "2":
                    openAccount(user);
                    break;
                case "3":
                    makeTransaction(user, TransactionType.DEPOSIT);
                    break;
                case "4":
                    makeTransaction(user, TransactionType.WITHDRAWAL);
                    break;
                case "5":
                    showHistory(user);
                    break;
                default:
                    System.out.println("Choose an option from 1 to 6.");
            }
        }
    }

    private void showAccounts(User user) {
        List<Account> accounts = store.accountsFor(user.username);
        if (accounts.isEmpty()) {
            System.out.println("No accounts yet. Choose 'Open account' to get started.");
            return;
        }
        System.out.println("Your accounts:");
        for (int index = 0; index < accounts.size(); index++) {
            Account account = accounts.get(index);
            System.out.printf(Locale.US, "%d. %s  %s%n", index + 1,
                    account.id, Money.format(account.balanceCents));
        }
    }

    private void openAccount(User user) throws IOException {
        Account account = new Account(UUID.randomUUID().toString(), user.username, 0L, Instant.now());
        store.accounts.put(account.id, account);
        store.save();
        System.out.println("Account opened: " + account.id);
    }

    private void makeTransaction(User user, TransactionType type) throws IOException {
        Account account = chooseAccount(user);
        if (account == null) {
            return;
        }
        String amountText = prompt("Amount in USD (for example, 25.00): ");
        if (amountText == null) {
            return;
        }
        long amountCents;
        try {
            amountCents = Money.parseCents(amountText);
        } catch (IllegalArgumentException exception) {
            System.out.println("Enter a positive amount with no more than two decimal places.");
            return;
        }
        if (type == TransactionType.WITHDRAWAL && amountCents > account.balanceCents) {
            System.out.println("Insufficient funds.");
            return;
        }

        long updatedBalance;
        try {
            updatedBalance = type == TransactionType.DEPOSIT
                    ? Math.addExact(account.balanceCents, amountCents)
                    : Math.subtractExact(account.balanceCents, amountCents);
        } catch (ArithmeticException exception) {
            System.out.println("That transaction exceeds the supported balance limit.");
            return;
        }
        account.balanceCents = updatedBalance;
        store.transactions.add(new Transaction(UUID.randomUUID().toString(), account.id,
                type, amountCents, Instant.now(), updatedBalance));
        store.save();
        System.out.println(type.label + " complete. New balance: " + Money.format(updatedBalance));
    }

    private void showHistory(User user) throws IOException {
        Account account = chooseAccount(user);
        if (account == null) {
            return;
        }
        boolean found = false;
        System.out.println("History for " + account.id + ":");
        for (Transaction transaction : store.transactions) {
            if (transaction.accountId.equals(account.id)) {
                System.out.printf(Locale.US, "%s  %-10s %s  balance %s%n",
                        transaction.timestamp, transaction.type.label,
                        Money.format(transaction.amountCents),
                        Money.format(transaction.balanceAfterCents));
                found = true;
            }
        }
        if (!found) {
            System.out.println("No transactions yet.");
        }
    }

    private Account chooseAccount(User user) throws IOException {
        List<Account> accounts = store.accountsFor(user.username);
        if (accounts.isEmpty()) {
            System.out.println("You do not have an account yet. Open one first.");
            return null;
        }
        for (int index = 0; index < accounts.size(); index++) {
            Account account = accounts.get(index);
            System.out.printf(Locale.US, "%d. %s  %s%n", index + 1,
                    account.id, Money.format(account.balanceCents));
        }
        String selection = prompt("Select account number: ");
        if (selection == null) {
            return null;
        }
        try {
            int index = Integer.parseInt(selection) - 1;
            if (index >= 0 && index < accounts.size()) {
                return accounts.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        System.out.println("That account selection is invalid.");
        return null;
    }

    private String prompt(String message) throws IOException {
        System.out.print(message);
        return input.readLine();
    }

    private char[] readPassword(String message) throws IOException {
        if (console != null) {
            return console.readPassword("%s", message);
        }
        String value = prompt(message);
        return value == null ? null : value.toCharArray();
    }
}

final class User {
    final String username;
    final byte[] salt;
    final byte[] passwordHash;

    User(String username, byte[] salt, byte[] passwordHash) {
        this.username = username;
        this.salt = salt;
        this.passwordHash = passwordHash;
    }
}

final class Account {
    final String id;
    final String ownerUsername;
    final Instant openedAt;
    long balanceCents;

    Account(String id, String ownerUsername, long balanceCents, Instant openedAt) {
        this.id = id;
        this.ownerUsername = ownerUsername;
        this.balanceCents = balanceCents;
        this.openedAt = openedAt;
    }
}

enum TransactionType {
    DEPOSIT("Deposit"),
    WITHDRAWAL("Withdrawal");

    final String label;

    TransactionType(String label) {
        this.label = label;
    }
}

final class Transaction {
    final String id;
    final String accountId;
    final TransactionType type;
    final long amountCents;
    final Instant timestamp;
    final long balanceAfterCents;

    Transaction(String id, String accountId, TransactionType type, long amountCents,
            Instant timestamp, long balanceAfterCents) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amountCents = amountCents;
        this.timestamp = timestamp;
        this.balanceAfterCents = balanceAfterCents;
    }
}

final class PasswordSecurity {
    private static final int ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordSecurity() { }

    static User createUser(String username, char[] password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new User(username, salt, derive(password, salt));
    }

    static boolean verify(User user, char[] password) throws GeneralSecurityException {
        return MessageDigest.isEqual(user.passwordHash, derive(password, user.salt));
    }

    static boolean consumeDummyWork(char[] password) throws GeneralSecurityException {
        byte[] dummySalt = new byte[SALT_BYTES];
        derive(password, dummySalt);
        return false;
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    static byte[] fromHex(String value) {
        if (value.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hexadecimal value.");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal value.");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}

final class Money {
    private Money() { }

    static long parseCents(String value) {
        try {
            long cents = new BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
            if (cents <= 0) {
                throw new IllegalArgumentException("Amount must be positive.");
            }
            return cents;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid amount.", exception);
        }
    }

    static String format(long cents) {
        return String.format(Locale.US, "USD %d.%02d", cents / 100, cents % 100);
    }
}

final class BankStore {
    final Map<String, User> users = new LinkedHashMap<>();
    final Map<String, Account> accounts = new LinkedHashMap<>();
    final List<Transaction> transactions = new ArrayList<>();
    private final Path dataFile;

    BankStore(Path dataFile) {
        this.dataFile = dataFile.toAbsolutePath().normalize();
    }

    void load() throws IOException {
        if (!Files.exists(dataFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
        int lineNumber = 0;
        try {
            for (String line : lines) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                switch (fields[0]) {
                    case "U":
                        loadUser(fields);
                        break;
                    case "A":
                        loadAccount(fields);
                        break;
                    case "T":
                        loadTransaction(fields);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown record type.");
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid bank data at line " + lineNumber + ".", exception);
        }
    }

    private void loadUser(String[] fields) {
        requireFieldCount(fields, 4);
        String username = fields[1];
        if (!username.equals(username.toLowerCase(Locale.ROOT))
                || !username.matches("[a-zA-Z0-9._-]{3,20}")) {
            throw new IllegalArgumentException("Invalid username.");
        }
        byte[] salt = PasswordSecurity.fromHex(fields[2]);
        byte[] hash = PasswordSecurity.fromHex(fields[3]);
        if (salt.length != 16 || hash.length != 32 || users.containsKey(username)) {
            throw new IllegalArgumentException("Invalid or duplicate user.");
        }
        users.put(username, new User(username, salt, hash));
    }

    private void loadAccount(String[] fields) {
        requireFieldCount(fields, 5);
        String id = UUID.fromString(fields[1]).toString();
        String owner = fields[2];
        long balance = Long.parseLong(fields[3]);
        Instant openedAt = Instant.parse(fields[4]);
        if (!users.containsKey(owner) || balance < 0 || accounts.containsKey(id)) {
            throw new IllegalArgumentException("Invalid or duplicate account.");
        }
        accounts.put(id, new Account(id, owner, balance, openedAt));
    }

    private void loadTransaction(String[] fields) {
        requireFieldCount(fields, 7);
        String id = UUID.fromString(fields[1]).toString();
        String accountId = UUID.fromString(fields[2]).toString();
        TransactionType type = TransactionType.valueOf(fields[3]);
        long amount = Long.parseLong(fields[4]);
        Instant timestamp = Instant.parse(fields[5]);
        long balanceAfter = Long.parseLong(fields[6]);
        if (!accounts.containsKey(accountId) || amount <= 0 || balanceAfter < 0
                || transactions.stream().anyMatch(transaction -> transaction.id.equals(id))) {
            throw new IllegalArgumentException("Invalid or duplicate transaction.");
        }
        transactions.add(new Transaction(id, accountId, type, amount, timestamp, balanceAfter));
    }

    private static void requireFieldCount(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("Unexpected number of fields.");
        }
    }

    List<Account> accountsFor(String username) {
        List<Account> result = new ArrayList<>();
        for (Account account : accounts.values()) {
            if (account.ownerUsername.equals(username)) {
                result.add(account);
            }
        }
        return result;
    }

    void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# SecureBank text data v1. Do not share this file; password hashes are not plaintext.");
        for (User user : users.values()) {
            lines.add(String.join("|", "U", user.username,
                    PasswordSecurity.toHex(user.salt), PasswordSecurity.toHex(user.passwordHash)));
        }
        for (Account account : accounts.values()) {
            lines.add(String.join("|", "A", account.id, account.ownerUsername,
                    Long.toString(account.balanceCents), account.openedAt.toString()));
        }
        for (Transaction transaction : transactions) {
            lines.add(String.join("|", "T", transaction.id, transaction.accountId,
                    transaction.type.name(), Long.toString(transaction.amountCents),
                    transaction.timestamp.toString(), Long.toString(transaction.balanceAfterCents)));
        }

        Path parent = dataFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "bank-data-", ".tmp");
        try {
            Files.write(temporaryFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporaryFile, dataFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
