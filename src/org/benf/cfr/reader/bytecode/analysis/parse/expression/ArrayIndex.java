package org.benf.cfr.reader.bytecode.analysis.parse.expression;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueCollector;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 16/03/2012
 * Time: 17:44
 * To change this template use File | Settings | File Templates.
 */
public class ArrayIndex implements Expression {
    private Expression array;
    private Expression index;

    public ArrayIndex(Expression array, Expression index) {
        this.array = array;
        this.index = index;
    }

    @Override
    public String toString() {

        return "" + array + "[" + index + "]";
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public Expression replaceSingleUsageLValues(LValueCollector lValueCollector) {
        array = array.replaceSingleUsageLValues(lValueCollector);
        index = index.replaceSingleUsageLValues(lValueCollector);
        return this;
    }

}