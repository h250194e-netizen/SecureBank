# Secure Bank Application

## Description
Secure Bank is a Java console application for registering and logging in to a bank account.
Users can open accounts, check balances, deposit and withdraw funds, and review saved transactions.

## Student Details
- Name: ANESU ZAMBUKO
- Registration Number: H250194E

## Features
- User registration and login with salted PBKDF2-HMAC-SHA-256 password hashes.
- Multiple bank accounts per user, with balances stored in integer cents.
- Deposits and withdrawals with amount validation and insufficient-funds checks.
- Complete per-account transaction history, including the balance after each transaction.
- Automatic text-file persistence across application restarts; no database required.
- Atomic data-file replacement to avoid partially written ledger snapshots.

## Requirements
- Java Development Kit (JDK) 11 or later.
- A terminal or command prompt.

## Compile and Run
From the repository root:

```powershell
javac -d out src/Main.java
java -cp out Main
```

The application creates `data/bank-data.txt` when the first user, account, or transaction is saved. To use a different data-file location, pass its path as the first argument:

```powershell
java -cp out Main path/to/bank-data.txt
```

Passwords are hidden when run in a terminal that provides Java's console. In IDEs without a console, password input falls back to standard input and may be visible while typing.

## Data and Security Notes
The ledger is a UTF-8 text file. User records contain a random salt and a PBKDF2 password hash, never the original password. Account balances and transaction amounts are stored as integer cents to avoid floating-point rounding errors. The file is replaced atomically where the filesystem supports it.

This project is an educational console application, not production banking software. The local data file is not encrypted or protected against deliberate modification; use test data only and restrict access to the file.

## Data Format
Each non-comment line is a pipe-delimited record:

- `U|username|salt-hex|password-hash-hex`
- `A|account-uuid|username|balance-cents|opened-at-utc`
- `T|transaction-uuid|account-uuid|DEPOSIT-or-WITHDRAWAL|amount-cents|timestamp-utc|balance-after-cents`

The checked-in `data/bank-data.txt` starts empty apart from its format header. Do not commit real passwords, account data, or transaction details to a public repository.