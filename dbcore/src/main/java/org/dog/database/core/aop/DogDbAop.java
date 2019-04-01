package org.dog.database.core.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.dog.core.common.ApplicationUtil;
import org.dog.core.entry.TccLock;
import org.dog.core.tccserver.ITccServer;
import org.dog.core.util.Pair;
import org.dog.core.util.ThreadManager;
import org.dog.database.core.ClazzInfo;
import org.dog.database.core.annotation.DogDb;
import org.dog.database.core.annotation.MatchType;
import org.dog.database.core.annotation.OperationType;
import org.dog.database.core.buffer.IDataBuffer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

@Component
@Aspect
public class DogDbAop {

    @Autowired
    ITccServer iTccServer;

    @Autowired
    IDataBuffer buffer;

    @Around("@annotation(org.dog.database.core.annotation.DogDb)  && @annotation(db)")
    public Object doAroundtransaction(ProceedingJoinPoint pjp, DogDb db) throws Throwable {

        Object result = null;

        AopHelper aopHelper = new AopHelper(pjp, db);

        try {

            /**
             * 在事务中的更新
             */
            if (ThreadManager.exsit() && db.type().equals(OperationType.UPDATEDATA)) {

                Pair<MatchType,Pair<Method, Object[]>>  methodAndObjs = aopHelper.getMethodAndArgObjects(db.queryMethodName());

                boolean multicall = methodAndObjs.getKey().equals(MatchType.IteratorMutiCall);

                Map<TccLock, Object> locks = new HashMap<>();

                Pair<Method, Object[]> query = methodAndObjs.getValue();

                Object queryObj = ApplicationUtil.getApplicationContext().getBean(db.queryClass());

                Object queryData = query.getKey().invoke(queryObj, query.getValue());

                if (!multicall) {

                    if (java.util.Optional.class.isAssignableFrom(queryData.getClass())) {

                        if (((Optional) queryData).isPresent()) {

                            locks.putAll(aopHelper.getLocksDogTableOrListOfDogTable(((java.util.Optional) queryData).get()));

                        }

                    } else {

                        if (queryData != null) {

                            locks.putAll(aopHelper.getLocksDogTableOrListOfDogTable(queryData));
                        }
                    }

                } else {

                    Object[] subDatas = (Object[]) queryData;

                    for (Object subData : subDatas) {

                        if (java.util.Optional.class.isAssignableFrom(subData.getClass())) {

                            if (((Optional) subData).isPresent()) {

                                locks.putAll(aopHelper.getLocksDogTableOrListOfDogTable(((java.util.Optional) subData).get()));

                            }

                        } else {

                            if (subData != null) {

                                locks.putAll(aopHelper.getLocksDogTableOrListOfDogTable(subData));
                            }
                        }

                    }
                }


                Set<TccLock> tobufferlocks = iTccServer.lock(locks.keySet());

                for (TccLock lock : tobufferlocks) {

                    buffer.buffData(lock, locks.get(lock));
                }

                Map<Object, Object> context = ThreadManager.getTccContext().getContext();

                ClazzInfo clazzInfo = new ClazzInfo(db.queryClass(), db.saveMethodName(),"");

                if (context.containsKey(clazzInfo)) {

                    Set<TccLock> values = (Set<TccLock>) context.get(clazzInfo);

                    values.addAll(tobufferlocks);


                } else {

                    context.put(clazzInfo, tobufferlocks);
                }

            }

            /**
             * 在事务中的插入
             */
            if (ThreadManager.exsit() && db.type().equals(OperationType.INSERTNEWDATA)) {

                Map<Object, Object> context = ThreadManager.getTccContext().getContext();

                ClazzInfo clazzInfo = new ClazzInfo(db.queryClass(), "",db.deleteMethodName());

                Map<TccLock, Object> locks = new HashMap<>();

                Pair<MatchType,Pair<Method, Object[]>>  methodAndObjs = aopHelper.getMethodAndArgObjects(db.deleteMethodName());

                if(methodAndObjs.getKey().equals(MatchType.ArgInParamter)){

                    locks.putAll(aopHelper.getLocksInParams());

                }

                if(methodAndObjs.getKey().equals(MatchType.ArgInDogTable)){

                    locks.putAll(aopHelper.getLocksInDogTable(pjp.getArgs()[0]));

                }

                if(methodAndObjs.getKey().equals(MatchType.Iterator)){

                      locks.putAll(aopHelper.getLocksInIterator((pjp.getArgs()[0])));
                }

                if(methodAndObjs.getKey().equals(MatchType.IteratorMutiCall)){

                    locks.putAll(aopHelper.getLocksInMutiIterator((pjp.getArgs()[0])));
                }

                Set<TccLock> tobufferlocks = iTccServer.lock(locks.keySet());

                for (TccLock lock : tobufferlocks) {

                    buffer.buffData(lock, locks.get(lock));
                }

                if (context.containsKey(clazzInfo)) {

                    Set<TccLock> values = (Set<TccLock>) context.get(clazzInfo);

                    values.addAll(tobufferlocks);

                } else {

                    context.put(clazzInfo, tobufferlocks);
                }

            }

            result = pjp.proceed();

        } catch (Exception e) {

            throw e;
        }

        return result;
    }

}

