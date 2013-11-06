package com.nflabs.zeppelin.zengine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.ScriptEngine;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.conf.HiveConf;

import org.apache.hadoop.hive.service.HiveInterface;
import org.apache.log4j.Logger;

import com.nflabs.zeppelin.conf.ZeppelinConfiguration;
import com.nflabs.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import com.nflabs.zeppelin.hive.HiveConnection;
import com.nflabs.zeppelin.result.Result;
import com.nflabs.zeppelin.result.ResultDataException;
import com.nflabs.zeppelin.util.Util;
import com.sun.script.jruby.JRubyScriptEngineFactory;


/**
 * Z class is abstract class for Zeppelin Plan.
 * Instances of Z class can construct liked list by pipe method.
 * @author moon
 *
 */
public abstract class Z {
	String id; // z object identifier
	Z prev;
	transient Z next;
	private Result result;
	private Result lastQueryResult;
	boolean executed = false;
	int maxResult = 10000;
	boolean webEnabled = false;
	private String name;
	private boolean autogenName;   // if autogenerated
	private boolean table;
	Map<String, Object> params = new HashMap<String, Object>();
	private Map<String, ParamInfo> paramInfos;
	transient static final String NAME_PREFIX="zp_";
	transient Connection connection;
	
	protected Z(){
		this.id = Integer.toString(hashCode());
	}

	/**
	 * Get id string
	 * @return id string of this object
	 */
	public String getId(){
		return id;
	}
	
	private Logger logger(){
		return Logger.getLogger(Z.class);
	}
	

	/**
	 * Add a paramter to pass template environment
	 * @param key name of parameter
	 * @param val value of parameter
	 * @return this object
	 */
	public Z withParam(String key, Object val){
		params.put(key, val);
		return this;
	}
	
	public Z withParams(Map<String, Object> params){
		this.params.putAll(params);
		return this;
	}
	
	
	public Map<String, Object> getParams(){
		return params;
	}
	
	
	public void setParams(Map<String, Object> params){
		this.params = params;
	}
	
	/**
	 * Set max number of rows. Data returned by result() method will have maximum maxResult number of rows. default 10000
	 * @param maxResult maximum number of rows, result() method want to return
	 * @return this Z object
	 */
	public Z withMaxResult(int maxResult){
		this.maxResult = maxResult;
		return this;
	}

	/**
	 * Pipe another Z instance. Current Z instance will be input of given instance by parameter.
	 * @param z Z instance to be piped. 
	 * @return Piped Z instance. (the same with passed from parameter)
	 */
	public Z pipe(Z z){
		unPipe();
		if(name()==null){
			name = NAME_PREFIX + this.hashCode();
			autogenName = true;
		}
		setNext(z);
		z.setPrev(this);
		return z;
	}
	
	/**
	 * Unlink pipe.
	 * @return this object
	 */
	public Z unPipe(){
		if(next()!=null){
			next().setPrev(null);
			setNext(null);
			
			if(autogenName==true){
				this.withName(null);
			}
		}
		return this;
	}
	
	/**
	 * Get previous Z instance linked by pipe.
	 * @return Previous Z instance. if there's no previous instance, return null
	 */
	public Z prev(){
		return prev;
	}
	/**
	 * Get next Z instance linked by pipe
	 * @return Next Z instance. if there's no next instance, return null
	 */
	public Z next(){
		return next;
	}
	
	/**
	 * Manually link previous Z instance. You should not use this method. use pipe() instead.
	 * Only for manually reconstructing Z plan linked list after deserialize.
	 *
	 * @param prev previous Z instance
	 */
	public void setPrev(Z prev){
		this.prev = prev;
	}

	/**
	 * Manually link next Z instance. You should not use this method. use pipe() instead.
	 * Only for manually reconstructing Z plan linked list after deserialize.
	 *
	 * @param next next Z instance
	 */

	public void setNext(Z next){
		this.next = next;
	}
	
	/**
	 * Get name. name can be null.
	 * name is table(view) name of result being saved
	 */
	public String name(){
		return name;
	}
	
	
	/**
	 * Set output table(view) name
	 * Execution of query will be saved in to this table(view)
	 * If name is null, out is not saved in the table(view).
	 * By default, name is automatically generated.
	 * @param name null if you don't want save the result into table(view). else the name of table(view) want to save
	 * @return
	 */
	public Z withName(String name){
		if(name==null){
			if(next()!=null){
				name = NAME_PREFIX + this.hashCode();
				autogenName = true;
			} else {
				autogenName = false;
			}
		}
		this.name = name;
		return this;
	}
	
	/**
	 * if name is set (by withName() method), execution result will be saved into table(view).
	 * this method controlls if table is used or view is used to save the result.
	 * by default view is used.
	 * @param table true for saving result into the table. false for saving result into view. default false
	 * @return
	 */
	public Z withTable(boolean table){
		this.table  = table;
		return this;
	}
	
	/**
	 * Check withTable setting
	 * @return
	 */
	public boolean isTable(){
		return table;
	}

	
	/**
	 * Get HiveQL compatible query to execute
	 * 
	 * @return HiveQL compatible query
	 * @throws ZException
	 */
	public abstract String getQuery() throws ZException;
	/**
	 * Resource files needed by query. (the query returned by getQuery())
	 * Hive uses 'add FILE' or 'add JAR' to add resource before execute query.
	 * Resources returned by this method will be automatically added.
	 * 
	 * @return list of URI of resources
	 * @throws ZException
	 */
	public abstract List<URI> getResources() throws ZException;
	
	/**
	 * Query to cleaning up this instance
	 * This query will be executed when it is being cleaned.
	 * @return HiveQL compatible query
	 * @throws ZException
	 */
	public abstract String getReleaseQuery() throws ZException;
	
	/**
	 * Get web resource of this Z instnace.
	 * This is gateway of visualization.
	 * 
	 * @param path resource path. 
	 * @return input stream of requested resource. or null if there's no such resource
	 * @throws ZException
	 */
	public abstract InputStream readWebResource(String path) throws ZException;
	
	/**
	 * Return if web is enabled.
	 * @return true if there's some resource can be returned by readWebResource().
	 *         false otherwise
	 */
	public abstract boolean isWebEnabled();
	
	protected abstract void initialize() throws ZException;
	
	/**
	 * Release all intermediate data(table/view) from this instance to head of linked(piped) z instance list
	 * @throws ZException
	 */
	public void release() throws ZException{
		initialize();
		if(executed==false) return;
		
		if(name()!=null && autogenName==true){
			if(table==true){
				executeQuery("DROP TABLE if exists "+name(), maxResult);
			} else {
				executeQuery("DROP VIEW if exists "+name(), maxResult);
			}
		}
		
		String q = getReleaseQuery();
		if(q!=null){
			executeQuery(q, maxResult);
		}
		
		if(prev()!=null){
			prev().release();
		}
	}
	
	/**
	 * Execute Z instance's query from head of this linked(piped) list to this instance.
	 * @return this object
	 * @throws ZException
	 */
	public Z execute() throws ZException{
		return execute(null);
	}
	/**
	 * Execute Z instance's query from head of this linked(piped) list to this instance.
	 * @return this object
	 * @throws ZException
	 */
	public Z execute(Connection connection) throws ZException{
		if(executed==true) return this;
		initialize();

		if(prev()!=null){
			prev().execute();
		}		
		String query = getQuery();
		if(query!=null){
			String[] queries = Util.split(query, ';');
			for(int i=0; i<queries.length; i++){
				String q = queries[i];
				if(i==queries.length-1){
					String tableCreation = null;
					if(name()==null){
						tableCreation = "";
					} else {
						if(isSaveableQuery(q)==false){
							throw new ZException("Can not save query "+q+" into table "+name());
						}
						if(isTable()){
							tableCreation = "CREATE TABLE "+name()+" AS ";
						} else {
							tableCreation = "CREATE VIEW "+name()+" AS ";
						}
					}
					q = tableCreation + q;
				}
				lastQueryResult = executeQuery(connection, q, maxResult);
			}
		}
		webEnabled = isWebEnabled();
		executed = true;
		return this;
	}
	
	/**
	 * @return 
	 * @throws ZException 
	 * 
	 */
	public Z dryRun() throws ZException{
		initialize();
		
		if(prev()!=null){
			prev().dryRun();
		}
		
		Map<String, ParamInfo> infos = extractParams();

		this.paramInfos = infos;
		return this;
	}
	
	protected abstract Map<String, ParamInfo> extractParams() throws ZException;
	
	public Map<String, ParamInfo> getParamInfos(){
		return paramInfos;
	}
	
	private boolean isSaveableQuery(String query){
		if(query==null) return false;
		String q = query.trim();
		q = q.toLowerCase();
		if(q.startsWith("select") ||
		   q.startsWith("map")){
			return true;
		} else {
			return false;
		}
		
		   
	}
		
	/**
	 * Get result of execution
	 * If there's name() defined, first it'll looking for table(or view) with name().
	 * And if table(or view) with name() exists, then read data from it and return as a result.
	 * 
	 * When name() is undefined(null), last query executed by execute() method will be the result.
	 * 
	 * @return result
	 * @throws ZException 
	 */
	public Result result() throws ZException{
		if(executed==false){
			throw new ZException("Can not get result because of this is not executed");
		}

		if(result==null){
			if(name()==null){ // unmaed
				if(lastQueryResult!=null){
					result = lastQueryResult;
				} else {
					if(prev()!=null){
						result = prev().result();
					}
				}
			} else { // named
				try{
					result = executeQuery("select * from "+name(), maxResult);
				} catch(Exception e){  // if table not found
					if(lastQueryResult!=null){
						result = lastQueryResult;
					}
				}
			}
		}
		
		return result;
	}
	
	/**
	 * Check if this instance is executed or not
	 * @return true if executed.
	 *         false if not executed
	 */
	public boolean isExecuted(){
		return executed;
	}
	
	synchronized private Connection connection() throws SQLException{
		return connection(null);
	}
	synchronized private Connection connection(Connection conn) throws SQLException{
		if(prev()==null){
			if(conn==null){
				if(connection==null){
					connection = getConnection();
				}	
			} else {
				connection = conn;
			}			
		} else {
			connection = prev().connection(conn);
		}
		
		return connection;
	}
	
	private Result executeQuery(String query, int max) throws ZException{
		return executeQuery(null, query, max);
	}
	
	
	private Result shellCommand(String cmd) throws ExecuteException, IOException, ResultDataException{
		logger().info("Run shell command '"+cmd+"'");
		CommandLine cmdLine = CommandLine.parse("bash");
		cmdLine.addArgument("-c", false);
		cmdLine.addArgument(cmd, false);
		DefaultExecutor executor = new DefaultExecutor();
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		executor.setStreamHandler(new PumpStreamHandler(outputStream));

		executor.setWatchdog(new ExecuteWatchdog(conf.getLong(ConfVars.ZEPPELIN_COMMAND_TIMEOUT)));
		int exitValue = executor.execute(cmdLine);
		String [] msgs = null;
		if (outputStream!=null){
			String msgstr = outputStream.toString();
			logger().debug("exec out="+msgstr);
			if(msgstr!=null){
				msgs = msgstr.split("\n");
			}
		}
		Result resultData = new Result(exitValue, msgs);
		return resultData;		
	}
	
	private Result executeQuery(Connection connection, String query, int max) throws ZException{
		initialize();
		if(query==null) return null;
		
		if(query.startsWith("!")){
			try {
				return shellCommand(query.substring(1));
			} catch (ExecuteException e) {
				logger().error(e);
			} catch (IOException e) {
				logger().error(e);
			} catch (ResultDataException e) {
				logger().error(e);
			}
		}
		
		
		Connection con = null;
		try {
			con = connection(connection);

			logger().debug("connection="+con);
			// add resources			
			List<URI> resources = getResources();

			for(URI res : resources){
				Statement stmt = con.createStatement();
				logger().info("add resource "+res.toString()); 
				if(res.getPath().endsWith(".jar")){
					stmt.executeQuery("add JAR "+res.toString());
				} else {
					stmt.executeQuery("add FILE "+res.toString());
					
				}
				stmt.close();
			}
			
			// execute query
			ResultSet res = null;
			Statement stmt = con.createStatement();
			logger().info("executeQuery : "+query);
			res = stmt.executeQuery(query);
			
			Result r = new Result(res, maxResult);
			r.load();
			stmt.close();
			return r;
		} catch (Throwable e) {
			try {
				if(con!=null){
					con.close();
				}
			} catch (Throwable e1) {
				logger().error("error on closing connection", e1);
			}
			throw new ZException(e);
		} 
	}
	

	public ScriptEngine getRubyScriptEngine(){
		return factory.getScriptEngine();
	}
	
	/**
	 * Configure Zeppelin environment.
	 * zeppelin-site.xml will be loaded from classpath.
	 * @throws ZException
	 */
	public static void configure() throws ZException{
		HiveInterface cli = null;
		configure(cli);
	}

	/**
	 * Configure Zeppelin environment.
	 * zeppelin-site.xml will be loaded from classpath.
	 * @param client hive client object
	 * @throws ZException
	 */
	public static void configure(HiveInterface client) throws ZException{
		ZeppelinConfiguration conf;
		try {
			conf = ZeppelinConfiguration.create();
		} catch (ConfigurationException e) {
			conf = new ZeppelinConfiguration();
		}

		configure(conf, client);
	}
	
	/**
	 * Configure Zeppelin with given configuration
	 * @param conf configuration to use
	 * @throws ZException
	 */
	public static void configure(ZeppelinConfiguration conf) throws ZException{
		configure(conf, null);
	}
	
	/**
	 * Configure Zeppelin with given configuration
	 * @param conf configuration to use
	 * @param client hive clinet object
	 * @throws ZException
	 */
	public static void configure(ZeppelinConfiguration conf, HiveInterface client) throws ZException{		
		try {
			Class.forName(conf.getString(ConfVars.HIVE_DRIVER));
		} catch (ClassNotFoundException e1) {
			throw new ZException(e1);
		}
		Z.conf = conf;		
		Z.factory = new JRubyScriptEngineFactory();
		Z.client = client;
		
		if(fs==null){
			try {
				fs = FileSystem.get(new org.apache.hadoop.conf.Configuration());
			} catch (IOException e) {
				throw new ZException(e);
			}
		}
	}
	
	public static Connection getConnection() throws SQLException{
		if(client!=null){
			return new HiveConnection(client);
		} else if(conf().getString(ConfVars.HIVE_CONNECTION_URI)==null || conf().getString(ConfVars.HIVE_CONNECTION_URI).trim().length()==0){
			return new HiveConnection(hiveConf());
		} else {
				return DriverManager.getConnection(conf().getString(ConfVars.HIVE_CONNECTION_URI));
		}
	}
	
	private static HiveConf hiveConf(){
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
	    if (classLoader == null) {
	      classLoader = Z.class.getClassLoader();
	    }
	    
		URL url = classLoader.getResource("hive-site.xml");
		HiveConf hiveConf = null;
		hiveConf = new HiveConf();
		if(url==null){
			// set some default configuration if no hive-site.xml provided
			hiveConf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, Z.conf().getString(ConfVars.ZEPPELIN_LOCAL_WAREHOUSE));
		}
		return hiveConf;		
	}
	
	private static ZeppelinConfiguration conf;
	private static JRubyScriptEngineFactory factory;
	private static FileSystem fs;
	private static HiveInterface client;
	
	/**
	 * Get zeppelin configuration
	 * @return
	 */
	public static ZeppelinConfiguration conf(){
		return conf;
	}

	/**
	 * Get filesystem object
	 * @return
	 */
	public static FileSystem fs(){
		return fs;
	}


}
