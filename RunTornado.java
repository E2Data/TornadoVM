import java.io.*;

public class RunTornado {

	public static void main(String[] args) {
		System.out.println("Execute init");
		String s = null;
		try {
			String[] command = {"/home/mary/Documents/tornado-installation/tornado/init.sh"};

			ProcessBuilder pb = new ProcessBuilder(command);
			Process p2 = pb.start(); 


			 Process p = Runtime.getRuntime().exec("tornado tornado.examples.HelloWorld");
			 BufferedReader stdInput = new BufferedReader(new 
                 		InputStreamReader(p.getInputStream()));

           		 BufferedReader stdError = new BufferedReader(new 
                 		InputStreamReader(p.getErrorStream()));

            		// read the output from the command
            		System.out.println("Here is the standard output of the command:\n");
            		while ((s = stdInput.readLine()) != null) {
                		System.out.println(s);
            		}
            
            		// read any errors from the attempted command
           		 System.out.println("Here is the standard error of the command (if any):\n");
           		 while ((s = stdError.readLine()) != null) {
                		System.out.println(s);
            		}
            
            		System.exit(0);
			// Runtime.getRuntime().exec("tornado tornado.examples.HelloWorld");
			/*Runtime.getRuntime().exec("chmod u+x etc/tornado.env");
			Runtime.getRuntime().exec(". etc/tornado.env");*/
		} catch (IOException e) {
			System.err.println("Caught IOException: " + e.getMessage());
		} 
		
	}

}
