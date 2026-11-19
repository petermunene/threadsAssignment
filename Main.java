import java.sql.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;

class database {
    private static final String URL = "jdbc:mysql://localhost:3306/bank_database";
    private static final String USER = "root";
    private static final String PASSWORD = "Z3n1thste3l!";//use your host password here

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
}
// Using synchronization
class BankAccount {
    private int balance = 100000;
    private ArrayList <String> audits = new ArrayList<String>();
    public synchronized void getAudits(){
        for (String audit:audits ){
            System.out.println("\n"+audit);
        }
    }
    public synchronized void deposit ( int amount) {
        if (amount <= 0){
            System.out.println("Deposit amount must be greater than 0.");
            return;
        }
        balance+=amount;
        audits.add(Thread.currentThread().getName()+" successfully deposited: "+amount+". Your new balance is: "+balance);
    }
    public synchronized void withdraw(int amount){
        if (balance<amount || amount<= 0){
            System.out.println("Invalid amount!");
            return;
        }
        balance-=amount;
        audits.add(Thread.currentThread().getName()+" successfully withdrawn: "+amount+". Your new balance is: "+balance);
    }
    public synchronized void moneyTransfer(int amount, BankAccount reciever){
        if (balance<amount || amount<= 0){
            System.out.println("Invalid amount! ");
            return;
        }
        balance-=amount;
        reciever.deposit(amount);
        audits.add(Thread.currentThread().getName()+" successfully transferred: "+amount+". Your new balance is: "+balance);
    }
    public synchronized void balance(){
        System.out.println("Final account balance: "+balance);
    }
}
// without using synchronization
class NonSynchronizedBankAccount {
    private int balance = 100000;
    private ArrayList <String> audits = new ArrayList<String>();
    public synchronized void getAudits(){
        for (String audit:audits ){
            System.out.println("\n"+audit);
        }
    }
    public void deposit ( int amount) {
        if (amount <= 0){
            System.out.println("Deposit amount must be greater than 0.");
            return;
        }
        balance+=amount;
        audits.add(Thread.currentThread().getName()+" successfully deposited: "+amount+". Your new balance is: "+balance);
    }
    public void withdraw(int amount){
        if (balance<amount || amount<= 0){
            System.out.println("Invalid amount!");
            return;
        }
        balance-=amount;
        audits.add(Thread.currentThread().getName()+" successfully withdraw: "+amount+". Your new balance is: "+balance);
    }
    public void moneyTransfer(int amount, NonSynchronizedBankAccount reciever){
        if (balance<amount || amount<= 0){
            System.out.println("Invalid amount! ");
            return;
        }
        balance-=amount;
        reciever.deposit(amount);
        audits.add(Thread.currentThread().getName()+" successfully transferred: "+amount+". Your new balance is: "+balance);
    }
    public void balance(){
        System.out.println("Final account balance: "+balance);
    }
}
// using ReentrantLock
class ReentrantLockBankAccount{
    private int id;
    private int balance =100000;
    private ReentrantLock lock = new ReentrantLock();
    private ArrayList <String> audits = new ArrayList<String>();
    public ReentrantLockBankAccount(int x){
        this.id=x;
    }
    public synchronized void getAudits(){
        for (String audit:audits ){
            System.out.println("\n"+audit);
        }
    }
    public void deposit ( int amount) {
        lock.lock();
        try{
            if (amount <= 0){
                System.out.println("Deposit amount must be greater than 0.");
                return;
            }
            balance+=amount;
            audits.add(Thread.currentThread().getName()+" successfully deposited: "+amount+". Your new balance is: "+balance);
            database.log(this.id, Thread.currentThread().getName(), "DEPOSIT",amount, balance);
        }finally{
            lock.unlock();
        }
    }
    public void withdraw(int amount){
        lock.lock();
        try{
            if (balance<amount || amount<= 0){
                System.out.println("Invalid amount!");
                return; 
            }
            balance-=amount;
            audits.add(Thread.currentThread().getName()+" successfully withdraw: "+amount+". Your new balance is: "+balance);
            database.log(this.id, Thread.currentThread().getName(),"WITHDRAW", amount, balance);
            
        } finally{
            lock.unlock();
        }
    }
    public void moneyTransfer(int amount, ReentrantLockBankAccount reciever){
        ReentrantLockBankAccount first = this.id<reciever.id?this:reciever;
        ReentrantLockBankAccount second = this.id>reciever.id?this:reciever;

        first.lock.lock();
        try{
            second.lock.lock();
            try{
                if (balance<amount || amount<= 0){
                    System.out.println("Invalid amount!");
                    return;
                }
                balance-=amount;
                reciever.balance+=amount;
                audits.add(Thread.currentThread().getName()+" successfully transferred: "+amount+". Your new balance is: "+balance);
                database.log(this.id, Thread.currentThread().getName(), "TRANSFER_OUT", amount, balance);
                reciever.audits.add(Thread.currentThread().getName()+" successfully deposited: "+amount+". Your new balance is: "+reciever.balance);
                database.log(reciever.id, Thread.currentThread().getName(), "DEPOSITED", amount, balance);
            }finally{
                second.lock.unlock();
            }
        }finally{first.lock.unlock();}
    }
    public void balance(){
        lock.lock();
        try{
            System.out.println("Final account Balance: "+balance);
            database.log(this.id, Thread.currentThread().getName(), "INQUIRY", 0, balance);
        }finally{
            lock.unlock();
        }
    }

}
// Main class 
public class Main{
    public static void main(String[] args){
    //NONSYNCHRONIZED IMPLIMENTATION
        NonSynchronizedBankAccount acc = new NonSynchronizedBankAccount();
        NonSynchronizedBankAccount acc2 = new NonSynchronizedBankAccount();
        // the threads represent the customers by the way
        Thread accThread1 = new Thread(()->acc.withdraw(20000),"customer A");
        Thread accThread2 = new Thread(()->acc.deposit(15000),"customer B");
        Thread accThread3 = new Thread(()->acc.moneyTransfer(30000,acc2), "customer C");
        Thread accThread4 = new Thread(()->acc.withdraw(25000),"customer D");
        Thread accThread5 = new Thread(()->acc.withdraw(10000),"customer E");
        Thread accThread6 = new Thread(()->acc.balance(),"customer F");

        accThread1.start();
        accThread2.start();
        accThread3.start();
        accThread4.start();
        accThread5.start();
        accThread6.start();
        try{
            accThread1.join();
            accThread2.join();
            accThread3.join();
            accThread4.join();
            accThread5.join();           
            accThread6.join();
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    //Synchronized bank account actions 
        BankAccount account = new BankAccount();
        BankAccount account2 = new BankAccount();
        // the threads represent the customers by the way
        Thread accountThread1 = new Thread(()->account.withdraw(20000),"Customer A");
        Thread accountThread2 = new Thread(()->account.deposit(15000),"Customer B");
        Thread accountThread3 = new Thread(()->account.moneyTransfer(30000,account2),"Customer C");
        Thread accountThread4 = new Thread(()->account.withdraw(25000),"Customer D");
        Thread accountThread5 = new Thread(()->account.withdraw(10000), "Customer E");
        Thread accountThread6 = new Thread(()->account.balance(),"Customer F");

        accountThread1.start();
        accountThread2.start();
        accountThread3.start();
        accountThread4.start();
        accountThread5.start();
        accountThread6.start();
        try{
            accountThread1.join();
            accountThread2.join();
            accountThread3.join();
            accountThread4.join();
            accountThread5.join();     
            accountThread6.join();      
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    //USING THE REENTRANTLOCK IMPLIMENTATION
        ReentrantLockBankAccount ac= new ReentrantLockBankAccount(1);
        ReentrantLockBankAccount ac2 = new ReentrantLockBankAccount(2);
        // the threads represent the customers by the way
        Thread acThread1 = new Thread(()->ac.withdraw(20000),"CUSTOMER A");
        Thread acThread2 = new Thread(()->ac.deposit(15000), "CUSTOMER B");
        Thread acThread3 = new Thread(()->ac.moneyTransfer(30000,ac2),"CUSTOMER C");
        Thread acThread4 = new Thread(()->ac.withdraw(25000),"CUSTOMER D");
        Thread acThread5 = new Thread(()->ac.withdraw(10000),"CUSTOMER E");
        Thread acThread6 = new Thread(()->ac.balance(), "CUSTOMER F");

        acThread1.start();
        acThread2.start();
        acThread3.start();
        acThread4.start();
        acThread5.start();
        acThread6.start();
        try{
            acThread1.join();
            acThread2.join();
            acThread3.join();
            acThread4.join();
            acThread5.join();    
            acThread6.join();       
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
        System.out.println("\n================ OUTPUTS==============");
        System.out.println("\n======NON-SYNCHRONIZED EXECUTION======");
        System.out.println("\n===acc====");
        acc.getAudits();
        System.out.println("\n===acc2===");
        acc2.getAudits();
        System.out.println("\n========SYNCHRONIZED EXECUTION========");
        System.out.println("\n=account=");
        account.getAudits();
        System.out.println("\n=account2=");
        account2.getAudits();
        System.out.println("\n======REENTRANTLOCK IMPLEMENTATION====");
        System.out.println("\n====ac====");
        ac.getAudits();
        System.out.println("\n===ac2====");
        ac2.getAudits();

    }
}
