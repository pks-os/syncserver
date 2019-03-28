package com.soffid.iam.sync.engine.db;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EmptyStackException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;

import org.mortbay.log.Log;
import org.mortbay.log.Logger;

import com.soffid.iam.config.Config;

import es.caib.seycon.ng.exception.InternalErrorException;
import es.caib.seycon.ng.sync.servei.TaskGenerator;

public class ConnectionPool {
    private static ConnectionPool thePool = null;
    Logger log = Log.getLogger("DB-ConnectionPool");
    private Hashtable<Thread,QueryConnectionInfo> currentDB = new Hashtable<Thread,QueryConnectionInfo>();
    private Stack<QueryConnectionInfo> freeMainConnections = new Stack<QueryConnectionInfo>();

    public enum ThreadBound { MASTER, BACKUP, ANY };
    
    ThreadLocal<ThreadBound> threadStatus = new ThreadLocal<ConnectionPool.ThreadBound>();
	private boolean driversRegistered = false;
    
	public static ConnectionPool getPool() {
        if (thePool == null) {
        	try
			{
				Config config = Config.getConfig();
	            thePool = new ConnectionPool();
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
        }
        return thePool;
    }

    
    /**
	 * 
	 */
	private void cancelAllConnections ()
	{
		for (QueryConnectionInfo qci: currentDB.values())
		{
			cancelConnection(qci);
		}
		
		Stack<QueryConnectionInfo> freeConnections = freeMainConnections;
		
		while (!freeConnections.isEmpty())
		{
			QueryConnectionInfo qci = freeConnections.pop();
			cancelConnection(qci);
		}
	}


	private void cancelConnection (QueryConnectionInfo qci)
	{
		qci.cancelled = true;
		try {
			qci.connection.close();
		} catch (SQLException e) 
		{
			
		}
	}
	/**
     * Clase que controla el uso de las conexiones SQL
     */
    protected class QueryConnectionInfo {
        /** número de usos realizados sobre la conexión */
        int locks;
        /** thread al cual es asignado */
        java.lang.Thread thread;
        /** conexión SQL */
        WrappedConnection connection;
        public String info;
        public boolean cancelled = false;
        public boolean uncancellable = false;
    }


    /**
     * Obtener acceso a una conexión de base de datos. Si el thread invocante
     * tiene alguna conexión ya asignada, se le retorna dicha conexión,
     * incrementando el número de bloqueos realizados.<BR>
     * Si no dispone de ninguna conexión ya asignada y hay alguna conexión sin
     * asignar a nadie, se le asigna<BR>
     * Si no dispone de conexión y están todas ocupadas, el sistema esperará
     * indefinidamente hasta que se libere. Para optimizar la espera se usa un
     * mecanismo de sincronización/notificación sobre el vector de conexiones
     * activas.
     * 
     * @return conexión SQL de uso por el thread invocante.
     * @throws InternalErrorException 
     */

    public Connection getPoolConnection() throws InternalErrorException {
		Stack<QueryConnectionInfo> freeConnections = freeMainConnections;
        QueryConnectionInfo qci = (QueryConnectionInfo) currentDB.get(Thread.currentThread());
        
        try {
            while  (qci == null )
            {
                qci = (QueryConnectionInfo) freeConnections.pop();
                Statement stmt = null;
                ResultSet rset = null;
                try {
                	stmt = qci.connection.createStatement();
                	String type = System.getProperty("dbDriverString"); //$NON-NLS-1$
                	if (type == null) {
                        try {
                            String driver = Config.getConfig().getDB();
                            type = getDriverType (driver);
                        } catch (Exception e) {
                            throw new RuntimeException("Unable to get dialect for database", e); //$NON-NLS-1$
                        }
                    }
                	rset = stmt.executeQuery (getDummyQuery(type));
                    rset.next();

                    try {
                        qci.connection.setThread();
                    } catch (SQLException e) {
                        log.warn("Error assigning SQL Connection", e);
                        qci = null;
                    }
                    
                } catch (Exception e) {
                    try {
                        //System.out.println ("Tancant conexió "+e.toString());
                        qci.connection.close();
                    } catch (SQLException e2) {
                    }
                    qci = null;
                } finally {
                    try {
                        if (rset != null)
                            rset.close();
                    } catch (SQLException e2) {
                    }
                    try {
                        if (stmt != null)
                            stmt.close();
                    } catch (SQLException e2) {
                    }
                }
                
            }
        } catch (EmptyStackException e) {
            Connection c;
            try {
           		c = createDatabaseConnection();
            } catch (Exception e1) {
                log.warn("Cannot connect to database", e1);
                throw new InternalErrorException("Cannot connect to database", e1);
            }
            qci = new QueryConnectionInfo();
            qci.locks = 0;
            qci.info = null;
            qci.thread = Thread.currentThread();
            qci.connection = new WrappedConnection(c);
            try {
                qci.connection.setThread();
            } catch (SQLException e1) {
                log.warn("Error assigning SQL Connection", e);
                throw new NullPointerException();
            }
        }
        currentDB.put(Thread.currentThread(),qci);
        qci.locks = qci.locks + 1;
        return qci.connection;
    }

    /**
	 * @param driver
	 * @return
	 */
	public String getDriverType (String driver)
	{
        String type = driver.substring(driver.indexOf(":")+1); //$NON-NLS-1$
        type = type.substring(0, type.indexOf(":")); //$NON-NLS-1$
        if (type.equals("mariadb")) type = "mysql";
        return type;
	}


	/**
	 * @param type
	 * @return
	 */
	public String getDummyQuery (String type)
	{
        if ("mysql".equals(type) || "postgresql".equals(type))  //$NON-NLS-1$
        {
        	return ("SELECT 1");
        } else if ("oracle".equals (type)) { //$NON-NLS-1$
        	return ("SELECT 1 FROM DUAL");
        } else if ("sqlserver".equals(type)) {
        	return ("SELECT 1 FROM sysobjects");
        } else {
            throw new RuntimeException("Unable to get dialect for database type ["+type+"]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
	}


	/**
     * Retornar al pool de conexiones una conexión obtenida mediante
     * {@link TaskGenerator#getQueryConnection}. Si el thread había solicitado
     * la conexión más de una vez, la conexión SQL no será todavía retornada al
     * pool, pero sí que se decrementará el número de usos que de ella se hacen.<BR>
     * Eventualmente se despertará y asignará la conexión a algun thread en
     * espera.
     * 
     * @param conn
     *                conexión a retornar al pool
     */
    public void releaseConnection(Connection conn) {
        releaseConnection();
    }

    /**
     * Retornar al pool de conexiones una conexión obtenida mediante
     * {@link TaskGenerator#getQueryConnection}. Si el thread había solicitado
     * la conexión más de una vez, la conexión SQL no será todavía retornada al
     * pool, pero sí que se decrementará el número de usos que de ella se hacen.<BR>
     * Eventualmente se despertará y asignará la conexión a algun thread en
     * espera.
     * 
     * @param conn
     *                conexión a retornar al pool
     */
    public void releaseConnection() {
        QueryConnectionInfo qci = (QueryConnectionInfo) currentDB.get(Thread.currentThread());
        if (qci != null)
        {
            qci.locks = qci.locks - 1;
            if (qci.locks <= 0)
            {
                try {
                	if (! qci.connection.getAutoCommit())
                	{
                		qci.connection.rollback();
                		qci.connection.setAutoCommit(true);
                	}
                } catch (SQLException e) {
                    
                }
                currentDB.remove (Thread.currentThread());
                qci.thread = null;
                try {
                    qci.connection.unsetThread();
                } catch (SQLException e) {
                    log.warn ("Error releasing connection", e);
                    throw new NullPointerException();
                }
                if (! qci.cancelled)
                {
            		Stack<QueryConnectionInfo> freeConnections = freeMainConnections;
                	freeConnections.push(qci);
                }
            }
        }
    }

    /**
     * Instancia una conexión a la base de datos. Utiliza el driver
     * 
     * @throws SQLException
     *                 error al conectar a la base de datos
     * @throws IOException
     * @throws FileNotFoundException
     */


	public Connection createDatabaseConnection() throws SQLException,
            FileNotFoundException, IOException {
        if (!driversRegistered ) {
            try {
                Class c = Class.forName("oracle.jdbc.driver.OracleDriver");
                DriverManager.registerDriver((java.sql.Driver) c.newInstance());
            } catch (Exception e) {
                log.info("Error registering driver: {}", e, null);
            }
            try {
                Class c = Class.forName("com.mysql.jdbc.Driver");
                DriverManager.registerDriver((java.sql.Driver) c.newInstance());
            } catch (Exception e) {
                log.info("Error registering driver: {}", e, null);
            }
            try{
            	Class c = Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            	log.info("Registering driver", c.getClass().getName(),null);
            	DriverManager.registerDriver((java.sql.Driver) c.newInstance());
            } catch (Exception e) {
            	log.info("Error registering driver: {}", e, null);
            }
            try{
            	Class c = Class.forName("org.postgresql.Driver");
            	DriverManager.registerDriver((java.sql.Driver) c.newInstance());
            } catch (Exception e) {
            	log.info("Error registering driver: {}", e, null);
            }
            driversRegistered = true;
        }

        Config config = Config.getConfig();
        // Connect to the database
        // You can put a database name after the @ sign in the connection URL.
       	return DriverManager.getConnection(config.getDB(), config.getDbUser(),
        	                config.getPassword().getPassword());
    }

    public synchronized String getStatus() {
        StringBuffer result = new StringBuffer ();
        int i = 0;
        for (Enumeration elems = currentDB.elements() ; elems.hasMoreElements() ;) {
            QueryConnectionInfo qci = (QueryConnectionInfo) elems.nextElement();
            result.append("Connection " + Integer.toString(++i)
                    + ": ");
            if (qci!=null && qci.connection != null) {
                result.append ("established");
                if (qci.locks > 0) {
                    result.append (" reserved "
                            + (qci.thread !=null? "by "+qci.thread.getName():"")
                            + " " + qci.locks
                            + " times");
                    if (qci.info != null)
                        result.append(qci.info);
                }
                result = result.append("\n");
            } else {
                result = result.append ( "disconnected\n" );
            }
        }
        // Afegim informació dels statements oberts
        if (WrappedConnection.openStatements!=null) {
        	int size = WrappedConnection.openStatements.size();
        	result = result.append("Number of open statements: "+size+"\n");
        	if (size!=0) {
        		String openSQLs = WrappedConnection.getOpenWrappedPreparedStatementsSQL().toString();
        		if (openSQLs!=null) openSQLs = openSQLs.replaceAll("#-#","\n"); //Afegim separador
        		result = result.append("Statements: \n"+openSQLs+"\n\n");
        	}
        }            
        result.append ("Free main connections: "+freeMainConnections.size());
        return result.toString();
    }

    public int getNumberOfConnections() {
        return currentDB.size() + freeMainConnections.size();
    }

    public int getNumberOfLockedConnections() {
        return currentDB.size();
    }

}
