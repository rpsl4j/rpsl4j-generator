/*
 * Copyright (c) 2015 Benjamin Roberts, Nathan Kelly, Andrew Maxwell
 * All rights reserved.
 */

package org.rpsl4j;

import java.io.IOException;
import java.util.HashMap;

import org.apache.commons.lang3.StringUtils;
import org.rpsl4j.emitters.OutputEmitter;
import org.rpsl4j.emitters.OutputEmitters;

import net.ripe.db.whois.common.io.RpslObjectFileReader;
import net.ripe.db.whois.common.io.RpslObjectStreamReader;
import net.ripe.db.whois.common.rpsl.RpslObject;

import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class App {

	public final static String APP_NAME = "rpsl4j-app";
	
	//CLI args
	@Parameter (names = {"-e", "--emitter"}, description = "Emitter to use to format output")
	protected String emitterName = null;
	
	@DynamicParameter (names = {"-m"}, description = "Emitter parameters")
	protected HashMap<String, String> emitterArguments = new HashMap<String, String>();
	
	@Parameter (names = {"-i", "--input"}, description = "[file]")
	protected String inputPath = null;
	
	@Parameter (names = {"-o", "--output"}, description = "[file]")
	protected String outputPath = null;
	
	//explanatory/help commands
	@Parameter (names = {"-h", "--help"}, help = true, description = "Dispaly usage information")
	protected boolean helpMode = false;
	
	@Parameter (names = {"--list-emitters"}, help = true, description = "List available emitters to format output with")
	protected boolean help_displayEmitters = false;
	
	
	protected OutputEmitter emitter;
	protected RpslObjectStreamReader reader;
	protected OutputWriter writer;
	

	//hardcoded usage string to avoid formatting quirks.
	public static final String USAGE_STRING_MANUAL = "Usage: " + APP_NAME + " [options]\n" + 
			"  Options:\n" + 
			"    -e, --emitter\n" + 
			"       Emitter to use to format output\n\n" + 
			"    -h, --help\n" + 
			"       Dispaly usage information\n\n" + 
			"    -i, --input\n" + 
			"       Input path (omit for stdin)\n\n" + 
			"    --list-emitters\n" + 
			"       List emitters available to format output with\n\n" + 
			"    -o, --output\n" + 
			"       Output path (omit for stdout)\n\n" + 
			"    -m\n" + 
			"       Emitter parameters (optional depending on emitter)\n" + 
			"       Syntax: -m key=value\n";
	
	protected static String usageString_autoGenerated;
	static {
		StringBuilder builder = new StringBuilder();
		new JCommander(new App()).usage(builder);
		usageString_autoGenerated = builder.toString();
	}
	
	public static String getUsageString() {
		return USAGE_STRING_MANUAL;
	}

	public static String getAvailableEmitters() {
		return "Available emitters: " + StringUtils.join(OutputEmitters.getEmitterList(), ", ");
	}
	
	/**
	 * Initialises the application
	 * @param args arguments to initialise the application with (should generally be passed through from main())
	 * @return True if application is now initialised and ready to continue. False if an error occurs, or a help flag was passed
	 * @throws ParameterException if error occurs in parsing CLI arguments
	 */
	protected boolean setup(String args[]) throws ParameterException { //changed to bool to enable easier testing.. yes, I know.. And in hindsight, better modularity in general..

		JCommander cliArgParser = new JCommander(this, args); //parse params - ParameterException may be thrown		
		
		if(helpMode || help_displayEmitters) //if a help mode was triggered, don't do any work, just alert the caller that some kind of help text should be displayed.
			return false;
		//else: we're not in help mode - go forth and configure the app for the real work
		
		
		//get emitter, initialise with arguments if they exist
		if(emitterArguments.size() > 0) {
			emitter = (emitterName != null) ? 
					(OutputEmitters.get(emitterName, emitterArguments)) :
					(OutputEmitters.get(OutputEmitters.DEFAULT_EMITTER, emitterArguments));
		} else {
			emitter = (emitterName != null) ? 
					(OutputEmitters.get(emitterName)) :
			        (OutputEmitters.get(OutputEmitters.DEFAULT_EMITTER));
		}
		
		reader = (inputPath != null) ?
				(new RpslObjectFileReader(inputPath)) :
				(new RpslObjectStreamReader(System.in));

		writer = new OutputWriter(emitter); //TODO: organise how to make this more extensible with relation to more elaborate output methods; eg ssh, restconf, etc. Not just file or stdout.
		
		return true;
	}
	

	/**
	 * Process rpsl into configured output. (Should only be run after setup())
	 */
	protected void run() {    	
    	//parse input into Rpsl objects..
    	for(String stringObject : reader)
    	{
    		//parse can return null or throw exceptions
    		try {
        		RpslObject object = RpslObject.parse(stringObject);
    			if (object == null)
    				throw new NullPointerException("Object failed to parse");
        		
        		writer.addObject(object);
    		} catch (NullPointerException | IllegalArgumentException e) {
    			//Object failed to parse, print error with excerpt of object
    			String[] splitObject = stringObject.split("\n");
    			System.err.println("Unable to parse following object, skipping... ");
    			
    			//Print object excerpt
    			for(int i = 0; i < 3 && i < splitObject.length; i++) {
    				System.err.println(splitObject[i]);
    				if(i == 2) //We only printed part of the object
    					System.err.println("...");
    			}
    		}
    	}
    	    	
    	//Emit objects to stdout or file depending on outputPath
    	if(outputPath==null) {
    		System.out.println(writer.toString());
    	} else {
			try {
				writer.writeToFile(outputPath);
			} catch (IOException e) {
				System.err.println("Error writing to file");
				System.exit(-1);
			}
		}
	}
	
	public static void main(String args[]) {
		App launcher = new App();
		try {
			if(launcher.setup(args)) //if more than help text required..
				launcher.run();
			else {
				if(launcher.helpMode)
					System.out.println(getUsageString());
				if(launcher.help_displayEmitters)
					System.out.println(getAvailableEmitters());
			}
		} catch (ParameterException e) {
			System.out.println("ERROR parsing app flags/parameters: " + e.getMessage());
			System.out.println(getUsageString());
			System.exit(1);
		}
	}
}
