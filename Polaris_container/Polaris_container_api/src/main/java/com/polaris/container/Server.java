package com.polaris.container;

public interface Server {
	void start();
	default Object getContext() {return null;}
	default void stop() {}
}
