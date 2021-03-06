package jef.database.routing.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import jef.database.DbUtils;
import jef.database.ORMConfig;
import jef.database.TransactionalSession;
import jef.database.jdbc.GenerateKeyReturnOper;
import jef.database.jdbc.JDBCTarget;
import jef.database.jsqlparser.RemovedDelayProcess;
import jef.database.jsqlparser.SqlFunctionlocalization;
import jef.database.jsqlparser.expression.Table;
import jef.database.jsqlparser.statement.select.Select;
import jef.database.jsqlparser.visitor.Statement;
import jef.database.meta.AbstractMetadata;
import jef.database.query.DbTable;
import jef.database.query.ParameterProvider;
import jef.database.routing.sql.ExecuteablePlan;
import jef.database.routing.sql.QueryablePlan;
import jef.database.routing.sql.SqlAnalyzer;
import jef.database.routing.sql.SqlAndParameter;
import jef.database.routing.sql.TableMetaCollector;
import jef.tools.StringUtils;

import com.google.common.collect.Multimap;

public class RoutingSQLExecutor implements SQLExecutor {
	private JDBCTarget db;
	private int fetchSize = ORMConfig.getInstance().getGlobalFetchSize();
	private int maxResult = 0;
	private Statement st;
	private SqlFunctionlocalization l;

	/**
	 * 从SQL语句加上返回类型构造
	 * 
	 * @param db
	 * @param sql
	 * @param resultClass
	 */
	public RoutingSQLExecutor(TransactionalSession db, Statement sql) {
		if (StringUtils.isEmpty(sql)) {
			throw new IllegalArgumentException("Please don't input an empty SQL.");
		}

		this.db = db.selectTarget(null);
		this.st = sql;
		l = new SqlFunctionlocalization(this.db.getProfile(), this.db);
		sql.accept(l);
	}

	/**
	 * 返回fetchSize
	 * 
	 * @return 每次游标获取的缓存大小
	 */
	public int getFetchSize() {
		return fetchSize;
	}

	/**
	 * 设置fetchSize
	 * 
	 * @param size
	 *            设置每次获取的缓冲大小
	 */
	public void setFetchSize(int size) {
		this.fetchSize = size;
	}

	/**
	 * 以迭代器模式返回查询结果
	 * 
	 * @return
	 * @throws SQLException
	 */
	public ResultSet getResultSet(int type, int concurrency, int holder, List<ParameterContext> params) throws SQLException {
		SqlAndParameter parse = getSqlAndParams(db, this, params);
		QueryablePlan plan = SqlAnalyzer.getSelectExecutionPlan((Select) parse.statement, parse.getParamsMap(), parse.params, db);
		return plan.getResultSet(parse, maxResult, fetchSize);
	}

	private SqlAndParameter getSqlAndParams(JDBCTarget db2, RoutingSQLExecutor jQuery, List<ParameterContext> params) {
		ContextProvider cp = new ContextProvider(params);
		SqlAndParameter sp = new SqlAndParameter(st, SqlAnalyzer.asValue(params), cp);
		if (l.delayLimit != null || l.delayStartWith != null) {
			sp.setInMemoryClause(new RemovedDelayProcess(l.delayLimit, l.delayStartWith));
		}
		return sp;
	}

	static class ContextProvider implements ParameterProvider {
		private List<ParameterContext> params;

		public ContextProvider(List<ParameterContext> params) {
			this.params = params;
		}

		@Override
		public Object getNamedParam(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object getIndexedParam(int index) {
			return params.get(index).getValue();
		}

		@Override
		public boolean containsParam(Object key) {
			if (key instanceof Integer) {
				return ((Integer) key) < params.size();
			} else {
				throw new UnsupportedOperationException();
			}
		}
	}

	/**
	 * 对于各种DDL、insert、update、delete等语句，不需要返回结果的，调用此方法来执行
	 * 
	 * @return 返回影响到的记录条数（针对update\delete）语句
	 */
	public UpdateReturn executeUpdate(GenerateKeyReturnOper generateKeys, List<ParameterContext> params) throws SQLException {
		SqlAndParameter parse = getSqlAndParams(db, this, params);
		Statement sql = parse.statement;

		ExecuteablePlan plan = SqlAnalyzer.getExecutionPlan(sql, parse.getParamsMap(), parse.params, db);
		return plan.processUpdate(generateKeys);
	}

	/**
	 * 限制返回的最大结果数
	 */
	public void setMaxResults(int maxResult) {
		this.maxResult = maxResult;
	}

	/**
	 * 得到查询所在的dbclient对象
	 * 
	 * @return
	 */
	public JDBCTarget getDb() {
		return db;
	}

	@Override
	public String toString() {
		return this.st.toString();
	}

	@Override
	public void setQueryTimeout(int queryTimeout) {
	}

	// Batch的约束，每个语句必是单库单表查询
	@Override
	public BatchReturn executeBatch(GenerateKeyReturnOper generateKeys, List<List<ParameterContext>> params) throws SQLException {
		TableMetaCollector collector = SqlAnalyzer.getTableMeta(st);
		if (collector.get() == null) {// 无需路由
			return processBatch(null, null, params, collector, generateKeys);
		}
		// 先按路由结果分组
		AbstractMetadata meta = collector.get();
		if (meta.getPartition() == null) {
			if (meta.getBindDsName() != null) {
				DbTable dbTable = meta.getBaseTable(db.getProfile());
				return processBatch(dbTable.getDbName(), null, params, collector, generateKeys);
			} else {
				return processBatch(null, null, params, collector, generateKeys);
			}
		}
		// 分库分表
		Multimap<String, List<ParameterContext>> result = SqlAnalyzer.doGroup(meta, params, this.st, this.db);
		BatchReturn ur = new BatchReturn();
		for (String s : result.keySet()) {
			int index = s.indexOf('-');
			String db = s.substring(0, index);
			String table = s.substring(index + 1);
			BatchReturn u = processBatch(db, table, params, collector, generateKeys);
			ur.merge(u.getBatchResult(), u.getGeneratedKeys());
		}
		return ur;
	}

	private BatchReturn processBatch(String database, String table, Collection<List<ParameterContext>> params, TableMetaCollector collector, GenerateKeyReturnOper oper) throws SQLException {
		JDBCTarget db;
		if (database != null && !database.equals(this.db.getDbkey())) {
			db = this.db.getTarget(database);
		} else {
			db = this.db;
		}
		String sql = getSql(table, collector);
		PreparedStatement st = null;
		try {
			st = oper.prepareStatement(db, sql);
			for (List<ParameterContext> record : params) {
				for (ParameterContext context : record) {
					context.apply(st);
				}
				st.addBatch();
			}
			BatchReturn result = new BatchReturn(st.executeBatch());
			oper.getGeneratedKey(result, st);
			return result;
		} finally {
			DbUtils.close(st);
		}

	}

	private String getSql(String table, TableMetaCollector collector) {
		if (table == null)
			return this.st.toString();
		for (Table t : collector.getModificationPoints()) {
			t.setReplace(table);
		}
		String s = this.st.toString();
		for (Table t : collector.getModificationPoints()) {
			t.removeReplace();
		}
		return s;
	}

}
