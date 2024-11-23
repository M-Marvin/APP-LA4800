package de.m_marvin.la4800;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import de.m_marvin.simplelogging.Log;
import de.m_marvin.simplelogging.api.Logger;
import de.m_marvin.simplelogging.impl.TagLogger;

public class Test {
	
	public static void main(String... args) throws InterruptedException {
		
		Logger logger = Log.defaultLogger();
		
		logger.info("Test LA4800");
		
		LA4800 la = new LA4800("COM9", new TagLogger(logger, "com"));
		
		try {
			logger.info("try connect ...");
			la.connect();
			
			Thread.sleep(5000);

			logger.info("try run single ...");
			CompletableFuture<String[]> s = la.command("LINES");
			
			logger.info("try query single ...");
			System.out.println(s.join().length);
			for (String str : s.join()) System.out.println(str);
			
			logger.info("try run multiple ...");
			CompletableFuture<String[]>[] r = la.command("MON", "LINES");
			
			logger.info("try query multiple ...");
			CompletableFuture.allOf(r).join();
			for (int i = 0; i < r.length; i++) {
				System.out.println(r[i].join().length);
				for (String str : r[i].join()) System.out.println(str);
			}
			
			Thread.sleep(1000);

			logger.info("disconnect ...");
			la.disconnect();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
}
