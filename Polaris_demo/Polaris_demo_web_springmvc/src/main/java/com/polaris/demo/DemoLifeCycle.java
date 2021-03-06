package com.polaris.demo;

import com.polaris.core.component.ManagedComponent;
import com.polaris.demo.rest.controller.DemoController;

/**
 * The DemoLifyCycle for generic components.
 * {@link DemoController}
 */
public class DemoLifeCycle extends ManagedComponent {

	@Override
	public void doStart() throws Exception {
		System.out.println("here is add resource");
	}

	@Override
	public void doStop() {
		System.out.println("here is release resource");
	}

	
}
