package postgresql;

import java.io.*;
import java.lang.*;
import java.net.*;
import java.util.*;
import java.sql.*;
import postgresql.*;

/**
 * @version 1.0 15-APR-1997
 * @author <A HREF="mailto:adrian@hottub.org">Adrian Hall</A>
 *
 * A Connection represents a session with a specific database.  Within the
 * context of a Connection, SQL statements are executed and results are
 * returned.
 *
 * A Connection's database is able to provide information describing
 * its tables, its supported SQL grammar, its stored procedures, the
 * capabilities of this connection, etc.  This information is obtained
 * with the getMetaData method.
 *
 * <B>Note:</B> By default, the Connection automatically commits changes
 * after executing each statement.  If auto-commit has been disabled, an
 * explicit commit must be done or database changes will not be saved.
 *
 * @see java.sql.Connection
 */
public class Connection implements java.sql.Connection 
{
	private PG_Stream pg_stream;

	private String PG_HOST;
	private int PG_PORT;
	private String PG_USER;
	private String PG_PASSWORD;
	private String PG_DATABASE;
	private boolean PG_STATUS;

	public boolean CONNECTION_OK = true;
	public boolean CONNECTION_BAD = false;

	private int STARTUP_CODE = 7;

	private boolean autoCommit = true;
	private boolean readOnly = false;
	
	private Driver this_driver;
	private String this_url;
	private String cursor = null;	// The positioned update cursor name

	/**
	 * Connect to a PostgreSQL database back end.
	 *
	 * @param host the hostname of the database back end
	 * @param port the port number of the postmaster process
	 * @param info a Properties[] thing of the user and password
	 * @param database the database to connect to
	 * @param u the URL of the connection
	 * @param d the Driver instantation of the connection
	 * @return a valid connection profile
	 * @exception SQLException if a database access error occurs
	 */
	public Connection(String host, int port, Properties info, String database, String url, Driver d) throws SQLException
	{
		int len = 288;			// Length of a startup packet

		this_driver = d;
		this_url = new String(url);
		PG_DATABASE = new String(database);
		PG_PASSWORD = new String(info.getProperty("password"));
		PG_USER = new String(info.getProperty("user"));
		PG_PORT = port;
		PG_HOST = new String(host);
		PG_STATUS = CONNECTION_BAD;

		try
		{
			pg_stream = new PG_Stream(host, port);
		} catch (IOException e) {
			throw new SQLException ("Connection failed: " + e.toString());
		}
		
		// Now we need to construct and send a startup packet
		try
		{
			pg_stream.SendInteger(len, 4);			len -= 4;
			pg_stream.SendInteger(STARTUP_CODE, 4);		len -= 4;
			pg_stream.Send(database.getBytes(), 64);	len -= 64;
			pg_stream.Send(PG_USER.getBytes(), len);
		} catch (IOException e) {
			throw new SQLException("Connection failed: " + e.toString());
		}
		ExecSQL(" ");				// Test connection
		PG_STATUS = CONNECTION_OK;
	}

	/**
	 * SQL statements without parameters are normally executed using
	 * Statement objects.  If the same SQL statement is executed many
	 * times, it is more efficient to use a PreparedStatement
	 *
	 * @return a new Statement object
	 * @exception SQLException passed through from the constructor
	 */
	public java.sql.Statement createStatement() throws SQLException
	{
		return new Statement(this);
	}

	/**
	 * A SQL statement with or without IN parameters can be pre-compiled
	 * and stored in a PreparedStatement object.  This object can then
	 * be used to efficiently execute this statement multiple times.
	 *
	 * <B>Note:</B> This method is optimized for handling parametric
	 * SQL statements that benefit from precompilation if the drivers
	 * supports precompilation.  PostgreSQL does not support precompilation.
	 * In this case, the statement is not sent to the database until the
	 * PreparedStatement is executed.  This has no direct effect on users;
	 * however it does affect which method throws certain SQLExceptions
	 *
	 * @param sql a SQL statement that may contain one or more '?' IN
	 *	parameter placeholders
	 * @return a new PreparedStatement object containing the pre-compiled
	 *	statement.
	 * @exception SQLException if a database access error occurs.
	 */
	public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException
	{
		return new PreparedStatement(this, sql);
	}

	/**
	 * A SQL stored procedure call statement is handled by creating a
	 * CallableStatement for it.  The CallableStatement provides methods
	 * for setting up its IN and OUT parameters and methods for executing
	 * it.
	 *
	 * <B>Note:</B> This method is optimised for handling stored procedure
	 * call statements.  Some drivers may send the call statement to the
	 * database when the prepareCall is done; others may wait until the
	 * CallableStatement is executed.  This has no direct effect on users;
	 * however, it does affect which method throws certain SQLExceptions
	 *
	 * @param sql a SQL statement that may contain one or more '?' parameter
	 *	placeholders.  Typically this statement is a JDBC function call
	 *	escape string.
	 * @return a new CallableStatement object containing the pre-compiled
	 *	SQL statement
	 * @exception SQLException if a database access error occurs
	 */
	public java.sql.CallableStatement prepareCall(String sql) throws SQLException
	{
		throw new SQLException("Callable Statements are not supported at this time");
//		return new CallableStatement(this, sql);
	}

	/**
	 * A driver may convert the JDBC sql grammar into its system's
	 * native SQL grammar prior to sending it; nativeSQL returns the
	 * native form of the statement that the driver would have sent.
	 *
	 * @param sql a SQL statement that may contain one or more '?'
	 *	parameter placeholders
	 * @return the native form of this statement
	 * @exception SQLException if a database access error occurs
	 */
	public String nativeSQL(String sql) throws SQLException
	{
		return sql;
	}

	/**
	 * If a connection is in auto-commit mode, than all its SQL
	 * statements will be executed and committed as individual
	 * transactions.  Otherwise, its SQL statements are grouped
	 * into transactions that are terminated by either commit()
	 * or rollback().  By default, new connections are in auto-
	 * commit mode.  The commit occurs when the statement completes
	 * or the next execute occurs, whichever comes first.  In the
	 * case of statements returning a ResultSet, the statement
	 * completes when the last row of the ResultSet has been retrieved
	 * or the ResultSet has been closed.  In advanced cases, a single
	 * statement may return multiple results as well as output parameter
	 * values.  Here the commit occurs when all results and output param
	 * values have been retrieved.
	 *
	 * @param autoCommit - true enables auto-commit; false disables it
	 * @exception SQLException if a database access error occurs
	 */
	public void setAutoCommit(boolean autoCommit) throws SQLException
	{
		if (this.autoCommit == autoCommit)
			return;
		if (autoCommit)
			ExecSQL("end");
		else
			ExecSQL("begin");
		this.autoCommit = autoCommit;
	}

	/**
	 * gets the current auto-commit state
	 * 
	 * @return Current state of the auto-commit mode
	 * @exception SQLException (why?)
	 * @see setAutoCommit
	 */
	public boolean getAutoCommit() throws SQLException
	{
		return this.autoCommit;
	}

	/**
	 * The method commit() makes all changes made since the previous
	 * commit/rollback permanent and releases any database locks currently
	 * held by the Connection.  This method should only be used when
	 * auto-commit has been disabled.  (If autoCommit == true, then we
	 * just return anyhow)
	 *
	 * @exception SQLException if a database access error occurs
	 * @see setAutoCommit
	 */
	public void commit() throws SQLException
	{
		if (autoCommit)
			return;
		ExecSQL("commit");
		autoCommit = true;
		ExecSQL("begin");
		autoCommit = false;
	}

	/**
	 * The method rollback() drops all changes made since the previous
	 * commit/rollback and releases any database locks currently held by
	 * the Connection. 
	 *
	 * @exception SQLException if a database access error occurs
	 * @see commit
	 */
	public void rollback() throws SQLException
	{
		if (autoCommit)
			return;
		ExecSQL("rollback");
		autoCommit = true;
		ExecSQL("begin");
		autoCommit = false;
	}

	/**
	 * In some cases, it is desirable to immediately release a Connection's
	 * database and JDBC resources instead of waiting for them to be
	 * automatically released (cant think why off the top of my head)
	 *
	 * <B>Note:</B> A Connection is automatically closed when it is
	 * garbage collected.  Certain fatal errors also result in a closed
	 * connection.
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void close() throws SQLException
	{
		if (pg_stream != null)
		{
			try
			{
				pg_stream.close();
			} catch (IOException e) {}
			pg_stream = null;
		}
	}

	/**
	 * Tests to see if a Connection is closed
	 *
	 * @return the status of the connection
	 * @exception SQLException (why?)
	 */
	public boolean isClosed() throws SQLException
	{
		return (pg_stream == null);
	}

	/**
	 * A connection's database is able to provide information describing
	 * its tables, its supported SQL grammar, its stored procedures, the
	 * capabilities of this connection, etc.  This information is made
	 * available through a DatabaseMetaData object.
	 *
	 * @return a DatabaseMetaData object for this connection
	 * @exception SQLException if a database access error occurs
	 */
	public java.sql.DatabaseMetaData getMetaData() throws SQLException
	{
//		return new DatabaseMetaData(this);
		throw new SQLException("DatabaseMetaData not supported");
	}

	/**
	 * You can put a connection in read-only mode as a hunt to enable
	 * database optimizations
	 *
	 * <B>Note:</B> setReadOnly cannot be called while in the middle
	 * of a transaction
	 *
	 * @param readOnly - true enables read-only mode; false disables it
	 * @exception SQLException if a database access error occurs
	 */
	public void setReadOnly (boolean readOnly) throws SQLException
	{
		this.readOnly = readOnly;
	}

	/**
	 * Tests to see if the connection is in Read Only Mode.  Note that
	 * we cannot really put the database in read only mode, but we pretend
	 * we can by returning the value of the readOnly flag
	 *
	 * @return true if the connection is read only
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isReadOnly() throws SQLException
	{
		return readOnly;
	}

	/**
	 * A sub-space of this Connection's database may be selected by
	 * setting a catalog name.  If the driver does not support catalogs,
	 * it will silently ignore this request
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void setCatalog(String catalog) throws SQLException
	{
		// No-op
	}

	/**
	 * Return the connections current catalog name, or null if no
	 * catalog name is set, or we dont support catalogs.
	 *
	 * @return the current catalog name or null
	 * @exception SQLException if a database access error occurs
	 */
	public String getCatalog() throws SQLException
	{
		return null;
	}

	/**
	 * You can call this method to try to change the transaction
	 * isolation level using one of the TRANSACTION_* values.  
	 *
	 * <B>Note:</B> setTransactionIsolation cannot be called while
	 * in the middle of a transaction
	 *
	 * @param level one of the TRANSACTION_* isolation values with
	 *	the exception of TRANSACTION_NONE; some databases may
	 *	not support other values
	 * @exception SQLException if a database access error occurs
	 * @see java.sql.DatabaseMetaData#supportsTransactionIsolationLevel
	 */
	public void setTransactionIsolation(int level) throws SQLException
	{
		throw new SQLException("Transaction Isolation Levels are not implemented");
	}
	
	/**
	 * Get this Connection's current transaction isolation mode.
	 * 
	 * @return the current TRANSACTION_* mode value
	 * @exception SQLException if a database access error occurs
	 */
	public int getTransactionIsolation() throws SQLException
	{
		return java.sql.Connection.TRANSACTION_SERIALIZABLE;
	}

	/**
	 * The first warning reported by calls on this Connection is
	 * returned.
	 *
	 * <B>Note:</B> Sebsequent warnings will be changed to this
	 * SQLWarning
	 *
	 * @return the first SQLWarning or null
	 * @exception SQLException if a database access error occurs
	 */
	public SQLWarning getWarnings() throws SQLException
	{
		return null;	// We handle warnings as errors
	}

	/**
	 * After this call, getWarnings returns null until a new warning
	 * is reported for this connection.
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void clearWarnings() throws SQLException
	{
		// Not handles since we handle wanrings as errors
	}

	// **********************************************************
	//		END OF PUBLIC INTERFACE
	// **********************************************************
	
	/**
	 * Send a query to the backend.  Returns one of the ResultSet
	 * objects.
	 *
	 * <B>Note:</B> there does not seem to be any method currently
	 * in existance to return the update count.
	 *
	 * @param sql the SQL statement to be executed
	 * @return a ResultSet holding the results
	 * @exception SQLException if a database error occurs
	 */
	public synchronized ResultSet ExecSQL(String sql) throws SQLException
	{
		Field[] fields = null;
		Vector tuples = new Vector();
		byte[] buf = new byte[sql.length()];
		int fqp = 0;
		boolean hfr = false;
		String recv_status = null, msg;
		SQLException final_error = null;

		if (sql.length() > 8192)
			throw new SQLException("SQL Statement too long: " + sql);
		try
		{
			pg_stream.SendChar('Q');
			buf = sql.getBytes();
			pg_stream.Send(buf);
			pg_stream.SendChar(0);
		} catch (IOException e) {
			throw new SQLException("I/O Error: " + e.toString());
		}

		while (!hfr || fqp > 0)
		{
			int c = pg_stream.ReceiveChar();
		
			switch (c)
			{
				case 'A':	// Asynchronous Notify
					int pid = pg_stream.ReceiveInteger(4);
					msg = pg_stream.ReceiveString(8192);
					break;
				case 'B':	// Binary Data Transfer
					if (fields == null)
						throw new SQLException("Tuple received before MetaData");
					tuples.addElement(pg_stream.ReceiveTuple(fields.length, true));
					break;
				case 'C':	// Command Status
					recv_status = pg_stream.ReceiveString(8192);
					if (fields != null)
						hfr = true;
					else
					{
						try
						{
							pg_stream.SendChar('Q');
							pg_stream.SendChar(' ');
							pg_stream.SendChar(0);
						} catch (IOException e) {
							throw new SQLException("I/O Error: " + e.toString());
						}
						fqp++;
					}
					break;
				case 'D':	// Text Data Transfer
					if (fields == null)
						throw new SQLException("Tuple received before MetaData");
					tuples.addElement(pg_stream.ReceiveTuple(fields.length, false));
					break;
				case 'E':	// Error Message
					msg = pg_stream.ReceiveString(4096);
					final_error = new SQLException(msg);
					hfr = true;
					break;
				case 'I':	// Empty Query
					int t = pg_stream.ReceiveChar();

					if (t != 0)
						throw new SQLException("Garbled Data");
					if (fqp > 0)
						fqp--;
					if (fqp == 0)
						hfr = true;
					break;
				case 'N':	// Error Notification
					msg = pg_stream.ReceiveString(4096);
					PrintStream log = DriverManager.getLogStream();
					log.println(msg);
					break;
				case 'P':	// Portal Name
					String pname = pg_stream.ReceiveString(8192);
					break;
				case 'T':	// MetaData Field Description
					if (fields != null)
						throw new SQLException("Cannot handle multiple result groups");
					fields = ReceiveFields();
					break;
				default:
					throw new SQLException("Unknown Response Type: " + (char)c);
			}
		}
		if (final_error != null)
			throw final_error;
		return new ResultSet(this, fields, tuples, recv_status, 1);
	}

	/**
	 * Receive the field descriptions from the back end
	 *
	 * @return an array of the Field object describing the fields
	 * @exception SQLException if a database error occurs
	 */
	private Field[] ReceiveFields() throws SQLException
	{
		int nf = pg_stream.ReceiveInteger(2), i;
		Field[] fields = new Field[nf];
		
		for (i = 0 ; i < nf ; ++i)
		{
			String typname = pg_stream.ReceiveString(8192);
			int typid = pg_stream.ReceiveInteger(4);
			int typlen = pg_stream.ReceiveInteger(2);
			fields[i] = new Field(this, typname, typid, typlen);
		}
		return fields;
	}

	/**
	 * In SQL, a result table can be retrieved through a cursor that
	 * is named.  The current row of a result can be updated or deleted
	 * using a positioned update/delete statement that references the
	 * cursor name.
	 *
	 * We support one cursor per connection.
	 *
	 * setCursorName sets the cursor name.
	 *
	 * @param cursor the cursor name
	 * @exception SQLException if a database access error occurs
	 */
	public void setCursorName(String cursor) throws SQLException
	{
		this.cursor = cursor;
	}
	
	/**
	 * getCursorName gets the cursor name.
	 *
	 * @return the current cursor name
	 * @exception SQLException if a database access error occurs
	 */
	public String getCursorName() throws SQLException
	{
		return cursor;
	}

	/**
	 * We are required to bring back certain information by
	 * the DatabaseMetaData class.  These functions do that.
	 *
	 * Method getURL() brings back the URL (good job we saved it)
	 *
	 * @return the url
	 * @exception SQLException just in case...
	 */
	public String getURL() throws SQLException
	{
		return this_url;
	}

	/**
	 * Method getUserName() brings back the User Name (again, we
	 * saved it)
	 *
	 * @return the user name
	 * @exception SQLException just in case...
	 */
	public String getUserName() throws SQLException
	{
		return PG_USER;
	}
}

// ***********************************************************************

//  This class handles all the Streamed I/O for a postgresql connection
class PG_Stream
{
	private Socket connection;
	private InputStream pg_input;
	private OutputStream pg_output;

	/**
	 * Constructor:  Connect to the PostgreSQL back end and return
	 * a stream connection.
	 *
	 * @param host the hostname to connect to
	 * @param port the port number that the postmaster is sitting on
	 * @exception IOException if an IOException occurs below it.
	 */
	public PG_Stream(String host, int port) throws IOException
	{
		connection = new Socket(host, port);
		pg_input = connection.getInputStream();
		pg_output = connection.getOutputStream();	
	}

	/**
	 * Sends a single character to the back end
	 *
	 * @param val the character to be sent
	 * @exception IOException if an I/O error occurs
	 */
	public void SendChar(int val) throws IOException
	{
		pg_output.write(val);
	}

	/**
	 * Sends an integer to the back end
	 *
	 * @param val the integer to be sent
	 * @param siz the length of the integer in bytes (size of structure)
	 * @exception IOException if an I/O error occurs
	 */
	public void SendInteger(int val, int siz) throws IOException
	{
		byte[] buf = new byte[siz];

		while (siz-- > 0)
		{
			buf[siz] = (byte)(val & 0xff);
			val >>= 8;
		}
		Send(buf);
	}

	/**
	 * Send an array of bytes to the backend
	 *
	 * @param buf The array of bytes to be sent
	 * @exception IOException if an I/O error occurs
	 */
	public void Send(byte buf[]) throws IOException
	{
		pg_output.write(buf);
	}

	/**
	 * Send an exact array of bytes to the backend - if the length
	 * has not been reached, send nulls until it has.
	 *
	 * @param buf the array of bytes to be sent
	 * @param siz the number of bytes to be sent
	 * @exception IOException if an I/O error occurs
	 */
	public void Send(byte buf[], int siz) throws IOException
	{
		int i;

		pg_output.write(buf, 0, (buf.length < siz ? buf.length : siz));
		if (buf.length < siz)
		{
			for (i = buf.length ; i < siz ; ++i)
			{
				pg_output.write(0);
			}
		}
	}

	/**
	 * Receives a single character from the backend
	 *
	 * @return the character received
	 * @exception SQLException if an I/O Error returns
	 */
	public int ReceiveChar() throws SQLException
	{
		int c = 0;
	
		try
		{
			c = pg_input.read();
			if (c < 0) throw new IOException("EOF");
		} catch (IOException e) {
			throw new SQLException("Error reading from backend: " + e.toString());
		}
		return c;
	}

	/**
	 * Receives an integer from the backend
	 *
	 * @param siz length of the integer in bytes
	 * @return the integer received from the backend
	 * @exception SQLException if an I/O error occurs
	 */
	public int ReceiveInteger(int siz) throws SQLException
	{
		int n = 0;
		
		try
		{
			for (int i = 0 ; i < siz ; i++)
			{
				int b = pg_input.read();
			
				if (b < 0)
					throw new IOException("EOF");
				n = n | (b >> (8 * i)) ;
			}
		} catch (IOException e) {
			throw new SQLException("Error reading from backend: " + e.toString());
		}
		return n;
	}

	/**
	 * Receives a null-terminated string from the backend.  Maximum of
	 * maxsiz bytes - if we don't see a null, then we assume something
	 * has gone wrong.
	 *
	 * @param maxsiz maximum length of string
	 * @return string from back end
	 * @exception SQLException if an I/O error occurs
	 */
	public String ReceiveString(int maxsiz) throws SQLException
	{
		byte[] rst = new byte[maxsiz];
		int s = 0;

		try
		{
			while (s < maxsiz)
			{
				int c = pg_input.read();
				if (c < 0)
					throw new IOException("EOF");
				else if (c == 0)
					break;
				else
					rst[s++] = (byte)c;
			}
			if (s >= maxsiz)
				throw new IOException("Too Much Data");
		} catch (IOException e) {
			throw new SQLException("Error reading from backend: " + e.toString());
		}
		String v = new String(rst, 0, s);
		return v;
	}

	/**
	 * Read a tuple from the back end.  A tuple is a two dimensional
	 * array of bytes
	 *
	 * @param nf the number of fields expected
	 * @param bin true if the tuple is a binary tuple
	 * @return null if the current response has no more tuples, otherwise
	 *	an array of strings
	 * @exception SQLException if a data I/O error occurs
	 */
	public byte[][] ReceiveTuple(int nf, boolean bin) throws SQLException
	{
		int i, bim = (nf + 7)/8;
		byte[] bitmask = Receive(bim);
		byte[][] answer = new byte[nf][0];

		int whichbit = 0x80;
		int whichbyte = 0;
		
		for (i = 0 ; i < nf ; ++i)
		{
			boolean isNull = ((bitmask[whichbyte] & whichbit) == 0);
			whichbit >>= 1;
			if (whichbit == 0)
			{
				++whichbyte;
				whichbit = 0x80;
			}
			if (isNull) 
				answer[i] = null;
			else
			{
				int len = ReceiveInteger(4);
				if (!bin) 
					len -= 4;
				if (len < 0) 
					len = 0;
				answer[i] = Receive(len);
			}
		}
		return answer;
	}

	/**
	 * Reads in a given number of bytes from the backend
	 *
	 * @param siz number of bytes to read
	 * @return array of bytes received
	 * @exception SQLException if a data I/O error occurs
	 */
	private byte[] Receive(int siz) throws SQLException
	{
		byte[] answer = new byte[siz];
		int s = 0;

		try 
		{
			while (s < siz)
			{
				int w = pg_input.read(answer, s, siz - s);
				if (w < 0)
					throw new IOException("EOF");
				s += w;
			}
		} catch (IOException e) {
			throw new SQLException("Error reading from backend: " + e.toString());
		}
		return answer;
	}
	
	/**
	 * Closes the connection
	 *
	 * @exception IOException if a IO Error occurs
	 */
	public void close() throws IOException
	{
		pg_output.close();
		pg_input.close();
		connection.close();
	}
}
