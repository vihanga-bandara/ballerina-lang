/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.database.table;

import org.ballerinalang.bre.Context;
import org.ballerinalang.database.sql.SQLDataIterator;
import org.ballerinalang.database.sql.SQLDatasource;
import org.ballerinalang.database.sql.SQLDatasourceUtils;
import org.ballerinalang.model.ColumnDefinition;
import org.ballerinalang.model.types.BStructureType;
import org.ballerinalang.model.values.BBoolean;
import org.ballerinalang.model.values.BFunctionPointer;
import org.ballerinalang.model.values.BInteger;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BTable;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.util.TableConstants;
import org.ballerinalang.util.TableResourceManager;
import org.ballerinalang.util.TableUtils;
import org.ballerinalang.util.codegen.StructureTypeInfo;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.ballerinalang.util.program.BLangFunctions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;

/**
 * Represents a reflection of a database table. Through a Mirrored table it is possible to add/remove data to/from a
 * database.
 *
 * @since 0.970.0
 */
public class BMirrorTable extends BTable {
    private SQLDatasource datasource;
    private String tableName;
    private StructureTypeInfo timeStructInfo;
    private StructureTypeInfo timeZoneStructInfo;
    private Calendar utcCalendar;

    public BMirrorTable(SQLDatasource datasource, String tableName, BStructureType constraintType,
                        StructureTypeInfo timeStructInfo, StructureTypeInfo timeZoneStructInfo, Calendar utcCalendar) {
        super(tableName, constraintType);
        this.datasource = datasource;
        this.tableName = tableName;
        this.timeStructInfo = timeStructInfo;
        this.timeZoneStructInfo = timeZoneStructInfo;
        this.utcCalendar = utcCalendar;
    }

    public void performAddOperation(BMap<String, BValue> data, Context context) {
        try {
            this.addData(data, context);
            context.setReturnValues();
        } catch (Throwable e) {
            context.setReturnValues(TableUtils.createTableOperationError(context, e));
            SQLDatasourceUtils.handleErrorOnTransaction(context);
        }
    }

    public void addData(BMap<String, BValue> data, Context context) {
        Connection conn = null;
        boolean isInTransaction = context.isInTransaction();
        try {
            conn = SQLDatasourceUtils.getDatabaseConnection(context, datasource, isInTransaction);
            String sqlStmt = TableUtils.generateInsertDataStatment(tableName, data);
            PreparedStatement stmt = conn.prepareStatement(sqlStmt);
            TableUtils.prepareAndExecuteStatement(stmt, data);
            reset(isInTransaction);
        } catch (SQLException e) {
            throw new BallerinaException("execute add failed: " + e.getMessage(), e);
        } finally {
            SQLDatasourceUtils.cleanupResources(conn, isInTransaction);
        }
    }

    public void performRemoveOperation(Context context, BFunctionPointer lambdaFunction) {
        int deletedCount = 0;
        Connection connection = null;
        boolean isInTransaction = context.isInTransaction();
        try {
            connection = SQLDatasourceUtils.getDatabaseConnection(context, this.datasource, isInTransaction);
            if (!isInTransaction) {
                connection.setAutoCommit(false);
            }
            while (this.hasNext(false)) {
                BMap<String, BValue> data = this.getNext();
                BValue[] args = { data };
                BValue[] returns = BLangFunctions.invokeCallable(lambdaFunction.value().getFunctionInfo(), args);
                if (((BBoolean) returns[0]).booleanValue()) {
                    ++deletedCount;
                    this.removeData(data, connection);
                }
            }
            if (!isInTransaction) {
                connection.commit();
            }
            context.setReturnValues(new BInteger(deletedCount));
            reset(isInTransaction);
        } catch (SQLException e) {
            context.setReturnValues(TableUtils.createTableOperationError(context, e));
            SQLDatasourceUtils.handleErrorOnTransaction(context);
        } finally {
            SQLDatasourceUtils.cleanupResources(connection, isInTransaction);
        }
    }

    public String stringValue() {
        return "";
    }

    private void removeData(BMap<String, BValue> data, Connection conn) throws SQLException {
        PreparedStatement stmt = null;
        try {
            String sqlStmt = TableUtils.generateDeleteDataStatment(tableName, data);
            stmt = conn.prepareStatement(sqlStmt);
            TableUtils.prepareAndExecuteStatement(stmt, data);
        } finally {
            // Shouldn't close the connection at this point, as it has to be handled at the higher level to delete
            // data transactional way
            if (stmt != null) {
                stmt.close();
            }
        }
    }

    protected void generateIterator() {
        PreparedStatement preparedStmt = null;
        ResultSet rs = null;
        Connection conn = datasource.getSQLConnection();
        try {
            String query = TableConstants.SQL_SELECT + tableName;
            preparedStmt = conn.prepareStatement(query);
            rs = preparedStmt.executeQuery();
            TableResourceManager rm = new TableResourceManager(conn, preparedStmt);
            List<ColumnDefinition> columnDefs = SQLDatasourceUtils.getColumnDefinitions(rs);
            this.iterator = new SQLDataIterator(utcCalendar, constraintType, timeStructInfo, timeZoneStructInfo, rm, rs,
                    columnDefs);
        } catch (SQLException e) {
            SQLDatasourceUtils.cleanupResources(rs, preparedStmt, conn, false);
            throw new BallerinaException("error in populating iterator for table : " + e.getMessage());
        }
    }
}
