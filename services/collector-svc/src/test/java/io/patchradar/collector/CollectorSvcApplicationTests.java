package io.patchradar.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "patchradar.poll.enabled=false")
class CollectorSvcApplicationTests {

	@Test
	void contextLoads() {
	}

}
