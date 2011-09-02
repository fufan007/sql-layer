/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.plan;

import com.akiban.server.error.UnsupportedSQLException;

import com.akiban.sql.types.DataTypeDescriptor;

import com.akiban.qp.expression.Expression;

/** An expression evaluated by a subquery.
 * TODO: Think about nested result sets.
 */
public class SubqueryExpression extends BaseExpression 
{
    private ResultSet subquery;

    public SubqueryExpression(ResultSet subquery, DataTypeDescriptor type) {
        super(type);
        this.subquery = subquery;
    }

    public ResultSet getSubquery() {
        return subquery;
    }

    @Override
    public String toString() {
        return subquery.toString();
    }

    public Expression generateExpression(ColumnExpressionToIndex fieldOffsets) {
        throw new UnsupportedSQLException("NIY", null);
    }
}
