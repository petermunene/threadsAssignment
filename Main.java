import java.sql.*;
import java.util.concurrent.locks.ReentrantLock;
// we set up the database first

/*
 ============================================================
          DATABASE SETUP INSTRUCTIONS
 ============================================================

 REQUIREMENTS:
 1. Install MySQL Server.
 2. Install a JDBC driver (MariaDB Connector/J or MySQL Connector/J).
 3. Ensure the MySQL service is running.

 STEP 1: OPEN MYSQL AS ADMINISTRATOR

    sudo mysql

 STEP 2: CREATE THE DATABASE

    CREATE DATABASE bank_database;
    USE bank_database;

 STEP 3: CREATE THE ACCOUNTS TABLE

    CREATE TABLE accounts (
        account_id INT PRIMARY KEY,
        customer_name VARCHAR(100) NOT NULL,
        balance INT NOT NULL
    );

 STEP 4: CREATE THE TRANSACTIONS TABLE

    CREATE TABLE transactions (
        transaction_id INT AUTO_INCREMENT PRIMARY KEY,
        account_id INT NOT NULL,
        customer_name VARCHAR(100),
        tx_type VARCHAR(30),
        amount INT,
        balance_after INT,
        FOREIGN KEY (account_id)
            REFERENCES accounts(account_id)
    );

 STEP 5: CREATE A DATABASE USER FOR THE JAVA APPLICATION

    CREATE USER 'bank_app'@'localhost'
    IDENTIFIED BY 'AStrongPassword';

    GRANT SELECT, INSERT, UPDATE, DELETE
    ON bank_database.*
    TO 'bank_app'@'localhost';

    FLUSH PRIVILEGES;

 STEP 6: EXIT MYSQL

    exit;

 STEP 7: COMPILE THE JAVA PROJECT

    javac -cp .:/usr/share/java/mariadb-java-client.jar *.java

 STEP 8: RUN THE APPLICATION

    java -cp .:/usr/share/java/mariadb-java-client.jar Main

 NOTE: no need to insert the accounts urself, the program creates
 accounts 1 to 8 on every run and clears old transactions so u only
 see the latest run

 ============================================================
          END OF DATABASE SETUP
 ============================================================
*/

class database {
    // works with the mariadb driver 2.x we have, if ur on driver 3.x change this to jdbc:mariadb://
    private static final String URL = "jdbc:mysql://localhost:3306/bank_database";
    private static final String USER = "bank_app";
    private static final String PASSWORD = "AStrongPassword";

    // runs once at the start. wipes the old transactions and puts every account back to 100000
    public static void reset() {
        String clearSql = "DELETE FROM transactions";
        String upsertSql = "INSERT INTO accounts (account_id, customer_name, balance) VALUES (?, ?, 100000) "
                         + "ON DUPLICATE KEY UPDATE balance = 100000";
        String[] owners = {"Peter", "John"};

        try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement clear = con.createStatement();
             PreparedStatement upsert = con.prepareStatement(upsertSql)) {
            clear.executeUpdate(clearSql);
            for (int id = 1; id <= 8; id++) {
                upsert.setInt(1, id);
                upsert.setString(2, owners[(id + 1) % 2]); // odd ids peter even ids john
                upsert.executeUpdate();
            }
        } catch (SQLException e) {
            System.out.println("DB Error (reset): " + e.getMessage());
        }
    }

    public static void log(int accountId, String customer, String action, int amount, int new_balance) {
        //define the query structure.
        String updateSql = "UPDATE accounts SET balance = ? WHERE account_id = ?";
        String insertSql = "INSERT INTO transactions (account_id, customer_name, tx_type, amount, balance_after) VALUES (?, ?, ?, ?, ?)";

        try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD)) {
            con.setAutoCommit(false);//prevents updating of the db until told so using commit()

            try (
                PreparedStatement upStmt = con.prepareStatement(updateSql);
                PreparedStatement inStmt = con.prepareStatement(insertSql)
            ) {
                if (!"INQUIRY".equals(action)) {
                    upStmt.setInt(1, new_balance);
                    upStmt.setInt(2, accountId);
                    upStmt.executeUpdate();
                }

                inStmt.setInt(1, accountId);
                inStmt.setString(2, customer);
                inStmt.setString(3, action);
                inStmt.setInt(4, amount);
                inStmt.setInt(5, new_balance);
                inStmt.executeUpdate();

                con.commit();//the release now it can update if no errors
            } catch (SQLException e) {
                con.rollback();//if any errors it will jump to this preventing erroneous entries
                System.out.println("DB Rollback: " + e.getMessage());
            }
        } catch (SQLException e) { //if connection fails
            System.out.println("DB Error: " + e.getMessage());
        }
    }

    // the audits now come from the transactions table, no more arraylists
    public static void printAudits(int accountId) {
        String sql = "SELECT customer_name, tx_type, amount, balance_after FROM transactions "
                   + "WHERE account_id = ? ORDER BY transaction_id";

        try (Connection con = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement st = con.prepareStatement(sql)) {
            st.setInt(1, accountId);
            try (ResultSet rs = st.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    String who = rs.getString("customer_name");
                    String type = rs.getString("tx_type");
                    int amount = rs.getInt("amount");
                    int after = rs.getInt("balance_after");

                    if ("INQUIRY".equals(type)) {
                        System.out.println("\n" + who + " checked the balance: " + after);
                    } else {
                        System.out.println("\n" + who + " successfully " + describe(type) + ": " + amount
                                + ". Your new balance is: " + after);
                    }
                }
                if (!found) {
                    System.out.println("\n(nothing logged for this account)");
                }
            }
        } catch (SQLException e) {
            System.out.println("DB Error (audits): " + e.getMessage());
        }
    }

    // turns the tx_type into words for printing
    private static String describe(String type) {
        switch (type) {
            case "DEPOSIT":      return "deposited";
            case "WITHDRAW":     return "withdrew";
            case "TRANSFER_OUT": return "transferred";
            case "TRANSFER_IN":  return "received";
            default:             return type;
        }
    }
}

// small helper, makes a thread sleep a bit so the race and the deadlock actually show up instead of us hoping they do
class Delay {
    static void nap(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// Using synchronization
// same slow read then write as the non synced one below but here only one thread gets in at a time so nothing gets lost
class BankAccount {
    private int id;
    private int balance = 100000;
    public BankAccount(int id){
        this.id = id;
    }
    public synchronized void deposit ( int amount) {
        if (amount <= 0){
            System.out.println("Deposit amount must be greater than 0.");
            return;
        }
        int current = balance;
        Delay.nap(10);
        balance = current + amount;
        database.log(id, Thread.currentThread().getName(), "DEPOSIT", amount, balance);
    }
    public synchronized void withdraw(int amount){
        if (amount <= 0){
            System.out.println("Invalid amount!");
            return;
        }
        if (balance < amount){
            System.out.println("Insufficient funds!");
            return;
        }
        int current = balance;
        Delay.nap(10);
        balance = current - amount;
        database.log(id, Thread.currentThread().getName(), "WITHDRAW", amount, balance);
    }
    // locks both accounts, smaller id always first so two transfers going opposite ways cant deadlock
    public void moneyTransfer(int amount, BankAccount reciever){
        BankAccount first = this.id < reciever.id ? this : reciever;
        BankAccount second = first == this ? reciever : this;

        synchronized (first) {
            synchronized (second) {
                if (amount <= 0){
                    System.out.println("Invalid amount!");
                    return;
                }
                if (balance < amount){
                    System.out.println("Insufficient funds!");
                    return;
                }
                int current = balance;
                Delay.nap(10);
                balance = current - amount;
                reciever.balance += amount;
                database.log(id, Thread.currentThread().getName(), "TRANSFER_OUT", amount, balance);
                database.log(reciever.id, Thread.currentThread().getName(), "TRANSFER_IN", amount, reciever.balance);
            }
        }
    }
    // just whatever the balance is at that moment, other customers might still be busy
    public synchronized void checkBalance(){
        System.out.println(Thread.currentThread().getName() + " checked balance: " + balance);
        database.log(id, Thread.currentThread().getName(), "INQUIRY", 0, balance);
    }
    public synchronized int getBalance(){
        return balance;
    }
}

// without using synchronization
// we read the balance, nap a bit, then write it back. other threads sneak in during the nap and their updates get overwritten
class NonSynchronizedBankAccount {
    private int id;
    private int balance = 100000;
    public NonSynchronizedBankAccount(int id){
        this.id = id;
    }
    public void deposit ( int amount) {
        if (amount <= 0){
            System.out.println("Deposit amount must be greater than 0.");
            return;
        }
        int current = balance;
        Delay.nap(10);
        balance = current + amount;
        database.log(id, Thread.currentThread().getName(), "DEPOSIT", amount, balance);
    }
    public void withdraw(int amount){
        if (amount <= 0){
            System.out.println("Invalid amount!");
            return;
        }
        if (balance < amount){
            System.out.println("Insufficient funds!");
            return;
        }
        int current = balance;
        Delay.nap(10);
        balance = current - amount;
        database.log(id, Thread.currentThread().getName(), "WITHDRAW", amount, balance);
    }
    public void moneyTransfer(int amount, NonSynchronizedBankAccount reciever){
        if (amount <= 0){
            System.out.println("Invalid amount!");
            return;
        }
        if (balance < amount){
            System.out.println("Insufficient funds!");
            return;
        }
        int current = balance;
        Delay.nap(10);
        balance = current - amount;
        reciever.balance += amount;
        database.log(id, Thread.currentThread().getName(), "TRANSFER_OUT", amount, balance);
        database.log(reciever.id, Thread.currentThread().getName(), "TRANSFER_IN", amount, reciever.balance);
    }
    public void checkBalance(){
        System.out.println(Thread.currentThread().getName() + " checked balance: " + balance);
        database.log(id, Thread.currentThread().getName(), "INQUIRY", 0, balance);
    }
    public int getBalance(){
        return balance;
    }
}

// using ReentrantLock but were are trying to introduce deliberate deadlocks
class DeadLockBankAccount {

    private int id;
    private int balance = 100000;

    private ReentrantLock lock = new ReentrantLock();

    public DeadLockBankAccount(int id) {
        this.id = id;
    }

    public void deposit(int amount) {
        lock.lock();

        try {
            if (amount <= 0) {
                System.out.println("Deposit amount must be greater than 0.");
                return;
            }

            balance += amount;

            database.log(
                this.id,
                Thread.currentThread().getName(),
                "DEPOSIT",
                amount,
                balance
            );

        } finally {
            lock.unlock();
        }
    }

    public void withdraw(int amount) {
        lock.lock();

        try {
            if (amount <= 0) {
                System.out.println("Invalid amount!");
                return;
            }
            if (balance < amount) {
                System.out.println("Insufficient funds!");
                return;
            }

            balance -= amount;

            database.log(
                this.id,
                Thread.currentThread().getName(),
                "WITHDRAW",
                amount,
                balance
            );

        } finally {
            lock.unlock();
        }
    }

    // DELIBERATELY UNSAFE: can cause deadlock
    // C locks acc 5 and D locks acc 6, both nap, then each one waits for the lock the other is holding. forever
    public void moneyTransfer(int amount, DeadLockBankAccount receiver) {

        // Lock the sender first
        lock.lock();

        try {
            Delay.nap(100); // gives the other thread time to grab its own lock, so the deadlock happens every run

            // Then attempt to lock the receiver
            receiver.lock.lock();

            try {
                if (amount <= 0) {
                    System.out.println("Invalid amount!");
                    return;
                }
                if (balance < amount) {
                    System.out.println("Insufficient funds!");
                    return;
                }

                balance -= amount;
                receiver.balance += amount;

                database.log(
                    this.id,
                    Thread.currentThread().getName(),
                    "TRANSFER_OUT",
                    amount,
                    balance
                );

                database.log(
                    receiver.id,
                    Thread.currentThread().getName(),
                    "TRANSFER_IN",
                    amount,
                    receiver.balance
                );

            } finally {
                receiver.lock.unlock();
            }

        } finally {
            lock.unlock();
        }
    }

    public void checkBalance() {
        lock.lock();

        try {
            System.out.println(Thread.currentThread().getName() + " checked balance: " + balance);

            database.log(
                this.id,
                Thread.currentThread().getName(),
                "INQUIRY",
                0,
                balance
            );

        } finally {
            lock.unlock();
        }
    }

    // no lock here on purpose, if the deadlock happened the locks are stuck and this would just hang too
    public int getBalance() {
        return balance;
    }
}

// using ReentrantLock but this time we actually solve the deadlock situation by always acquiring the locks for different accounts in a specific sequence!
class ReentrantLockBankAccount{
    private int id;
    private int balance =100000;
    private ReentrantLock lock = new ReentrantLock();
    public ReentrantLockBankAccount(int x){
        this.id=x;
    }
    public void deposit ( int amount) {
        lock.lock();
        try{
            if (amount <= 0){
                System.out.println("Deposit amount must be greater than 0.");
                return;
            }
            balance+=amount;
            database.log(this.id, Thread.currentThread().getName(), "DEPOSIT",amount, balance);
        }finally{
            lock.unlock();
        }
    }
    public void withdraw(int amount){
        lock.lock();
        try{
            if (amount <= 0){
                System.out.println("Invalid amount!");
                return;
            }
            if (balance<amount){
                System.out.println("Insufficient funds!");
                return;
            }
            balance-=amount;
            database.log(this.id, Thread.currentThread().getName(),"WITHDRAW", amount, balance);

        } finally{
            lock.unlock();
        }
    }
    // same nap as the deadlock version so u can see its the lock order fixing it and not just luck
    public void moneyTransfer(int amount, ReentrantLockBankAccount reciever){
        ReentrantLockBankAccount first = this.id<reciever.id?this:reciever;
        ReentrantLockBankAccount second = first==this?reciever:this;

        first.lock.lock();
        try{
            Delay.nap(100);
            second.lock.lock();
            try{
                if (amount <= 0){
                    System.out.println("Invalid amount!");
                    return;
                }
                if (balance<amount){
                    System.out.println("Insufficient funds!");
                    return;
                }
                balance-=amount;
                reciever.balance+=amount;
                database.log(this.id, Thread.currentThread().getName(), "TRANSFER_OUT", amount, balance);
                database.log(reciever.id, Thread.currentThread().getName(), "TRANSFER_IN", amount, reciever.balance);
            }finally{
                second.lock.unlock();
            }
        }finally{first.lock.unlock();}
    }
    public void checkBalance(){
        lock.lock();
        try{
            System.out.println(Thread.currentThread().getName() + " checked balance: "+balance);
            database.log(this.id, Thread.currentThread().getName(), "INQUIRY", 0, balance);
        }finally{
            lock.unlock();
        }
    }
    public int getBalance(){
        lock.lock();
        try{
            return balance;
        }finally{
            lock.unlock();
        }
    }
}

// Main class
public class Main{

    // start all the customers then wait for every one of them to finish
    static void runCustomers(Thread... customers){
        for (Thread t : customers){
            t.start();
        }
        try{
            for (Thread t : customers){
                t.join();
            }
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    static void printSection(String title, int id1, int bal1, int expected1, int id2, int bal2, int expected2){
        System.out.println("\n======" + title + "======");
        System.out.println("\n=== account " + id1 + " ===");
        database.printAudits(id1);
        System.out.println("\nFinal balance: " + bal1 + " (expected " + expected1 + ")");
        System.out.println("\n=== account " + id2 + " ===");
        database.printAudits(id2);
        System.out.println("\nFinal balance: " + bal2 + " (expected " + expected2 + ")");
    }

    public static void main(String[] args){
        database.reset();

    //NONSYNCHRONIZED IMPLIMENTATION (accounts 1 and 2)
        NonSynchronizedBankAccount acc = new NonSynchronizedBankAccount(1);
        NonSynchronizedBankAccount acc2 = new NonSynchronizedBankAccount(2);
        // the threads represent the customers by the way
        runCustomers(
            new Thread(()->acc.withdraw(20000),"customer A"),
            new Thread(()->acc.deposit(15000),"customer B"),
            new Thread(()->acc.moneyTransfer(30000,acc2), "customer C"),
            new Thread(()->acc.withdraw(25000),"customer D"),
            new Thread(()->acc.withdraw(10000),"customer E"),
            new Thread(()->acc.checkBalance(),"customer F")
        );

    //Synchronized bank account actions (accounts 3 and 4)
        BankAccount account = new BankAccount(3);
        BankAccount account2 = new BankAccount(4);
        runCustomers(
            new Thread(()->account.withdraw(20000),"Customer A"),
            new Thread(()->account.deposit(15000),"Customer B"),
            new Thread(()->account.moneyTransfer(30000,account2),"Customer C"),
            new Thread(()->account.withdraw(25000),"Customer D"),
            new Thread(()->account.withdraw(10000), "Customer E"),
            new Thread(()->account.checkBalance(),"Customer F")
        );

    //USING THE REENTRANT LOCK BUT WE ARE DELIBERATELY GOING TO SET UP A DEADLOCK (accounts 5 and 6)
        DeadLockBankAccount dlAccount1 =new DeadLockBankAccount(5);
        DeadLockBankAccount dlAccount2 =new DeadLockBankAccount(6);

        // C and D transfer in opposite directions at the same time, thats the deadlock
        Thread[] dlThreads = {
            new Thread(() -> dlAccount1.withdraw(20000),"CUSTOMER A"),
            new Thread(() -> dlAccount1.deposit(15000),"CUSTOMER B"),
            new Thread(() -> dlAccount1.moneyTransfer(30000, dlAccount2),"CUSTOMER C"),
            new Thread(() -> dlAccount2.moneyTransfer(25000, dlAccount1),"CUSTOMER D"),
            new Thread(() -> dlAccount1.withdraw(10000),"CUSTOMER E"),
            new Thread(() -> dlAccount1.checkBalance(),"CUSTOMER F")
        };

        // daemon so the stuck threads dont stop the program from exiting at the end
        for (Thread t : dlThreads){
            t.setDaemon(true);
            t.start();
        }

        // we cant just join() like normal, stuck threads never finish. so we give them 3 seconds max
        long deadline = System.currentTimeMillis() + 3000;
        try{
            for (Thread t : dlThreads){
                t.join(Math.max(1, deadline - System.currentTimeMillis()));
            }
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }

        boolean deadlocked = false;
        for (Thread t : dlThreads){
            if (t.isAlive()){
                deadlocked = true;
                System.out.println("DEADLOCK: " + t.getName() + " is still stuck waiting for a lock");
            }
        }
        if (!deadlocked){
            System.out.println("no deadlock this time (got lucky)");
        }

    //USING THE REENTRANTLOCK IMPLIMENTATION BUT WE HAVE SOLVED THE DEADLOCK (accounts 7 and 8)
        // exact same customers as the deadlock one, opposite transfers included, but this time it finishes
        ReentrantLockBankAccount ac= new ReentrantLockBankAccount(7);
        ReentrantLockBankAccount ac2 = new ReentrantLockBankAccount(8);
        runCustomers(
            new Thread(()->ac.withdraw(20000),"CUSTOMER A"),
            new Thread(()->ac.deposit(15000), "CUSTOMER B"),
            new Thread(()->ac.moneyTransfer(30000,ac2),"CUSTOMER C"),
            new Thread(()->ac2.moneyTransfer(25000,ac),"CUSTOMER D"),
            new Thread(()->ac.withdraw(10000),"CUSTOMER E"),
            new Thread(()->ac.checkBalance(), "CUSTOMER F")
        );

        // expected = what the balance should be if no update got lost
        System.out.println("\n================ OUTPUTS==============");
        printSection("NON-SYNCHRONIZED EXECUTION", 1, acc.getBalance(), 30000, 2, acc2.getBalance(), 130000);
        printSection("SYNCHRONIZED EXECUTION", 3, account.getBalance(), 30000, 4, account2.getBalance(), 130000);
        printSection("DEADLOCK EXECUTION", 5, dlAccount1.getBalance(), 80000, 6, dlAccount2.getBalance(), 105000);
        if (deadlocked){
            System.out.println("\n(deadlock balances are off cause the stuck transfers and the customers waiting behind them never ran)");
        }
        printSection("REENTRANTLOCK IMPLEMENTATION", 7, ac.getBalance(), 80000, 8, ac2.getBalance(), 105000);
    }
}
