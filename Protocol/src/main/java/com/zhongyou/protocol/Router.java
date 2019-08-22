package com.zhongyou.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Router<KeyType, AcceptorType, DataType> {
	private Map<KeyType, Set<AcceptorType>> mRouterTable = new HashMap<>();
	private KeyMapper<KeyType, DataType> mKeyMapper;
	private AcceptorHandler<AcceptorType, DataType> mAcceptorHandler;

	public Router(AcceptorHandler<AcceptorType, DataType> acceptorHandler) {
		this(null, acceptorHandler);
	}

	public Router(KeyMapper<KeyType, DataType> keyMapper, AcceptorHandler<AcceptorType, DataType> acceptorHandler) {
		mKeyMapper = keyMapper;
		mAcceptorHandler = acceptorHandler;
	}

	public void register(KeyType key, AcceptorType value) {
		Set<AcceptorType> acceptors = mRouterTable.get(key);
		if (acceptors == null) {
			acceptors = new HashSet<>();
			mRouterTable.put(key, acceptors);
		}
		acceptors.add(value);
	}

	public void unregister(KeyType key, AcceptorType value) {
		Set<AcceptorType> acceptors = mRouterTable.get(key);
		if (acceptors != null) {
			acceptors.remove(value);
			if (acceptors.isEmpty()) {
				mRouterTable.remove(key);
			}
		}
	}

	public Collection<AcceptorType> unregister(KeyType key) {
		Set<AcceptorType> acceptors = new HashSet<>();
		if (mRouterTable.containsKey(key)) {
			acceptors.addAll(mRouterTable.get(key));
		}
		return acceptors;
	}

	public Collection<KeyType> getRegisteredKeys() {
		return new HashSet<>(mRouterTable.keySet());
	}

	public boolean route(DataType data) {
		return route(mKeyMapper.map(data), data);
	}

	public boolean route(KeyType key, DataType data) {
		Set<AcceptorType> acceptors = mRouterTable.get(key);
		if (acceptors == null) {
			return false;
		}
		if (acceptors.isEmpty()) {
			return false;
		}
		for (AcceptorType acceptor : acceptors) {
			mAcceptorHandler.handle(acceptor, data);
		}
		return true;
	}

	public interface KeyMapper<KeyType, DataType> {
		KeyType map(DataType data);
	}

	public interface AcceptorHandler<AcceptorType, DataType> {
		void handle(AcceptorType acceptor, DataType data);
	}
}
