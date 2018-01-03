import uuidv4 from 'uuid/v4';
import {getPk, getRowByPk} from "../util/db";

export const insertRow = (table, record) => {
    return {
        type: "INSERT",
        table,
        record: {...record}
    }
};

export const updateRow = (tableName, record, db) => {
    const table = db.tables[tableName];
    const pk = getPk(record, table);
    const prior = getRowByPk(pk, table);
    return {
        type: "UPDATE",
        table: tableName,
        record: {...record},
        prior
    }
};

export const deleteRow = (table, record) => {
    return {
        type: "DELETE",
        table,
        record: {...record},
        prior: {...record}
    }
};

export const clearDb = () => {
    return {
        type: 'CLEAR_DB'
    }
};

export const clearedDb = () => {
    return {
        type: 'CLEARED_DB'
    }
};

export const createTxn = (changes) => {
    const txnId = uuidv4();
    for(let change of changes) {
        switch(change.type) {
            case "INSERT":
                change.record.prvTxnId = undefined;
                change.record.curTxnId = txnId;
                break;
            case "UPDATE":
                change.record.prvTxnId = change.prior.curTxnId;
                change.record.curTxnId = txnId;
                break;
            case "DELETE":
                change.record.prvTxnId = change.record.curTxnId;
                change.record.curTxnId = txnId;
                break;
            default:
                throw new Error(`Type not implemented: ${change.type}`)
        }
    }
    return {
        type: "COMMIT",
        txn: {
            id: txnId,
            changes
        }
    }
};