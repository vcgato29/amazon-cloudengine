package cloudworker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;


public class RemoteWorker {
	static AmazonEC2 ec2;
	static AmazonSQS sqs;
	static DynamoDBService dynamoDB;
	
	private static void init() throws Exception {
		/*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */
	     AWSCredentials credentials = null;
	        try {
	            credentials = new ProfileCredentialsProvider().getCredentials();
	        } catch (Exception e) {
	            throw new AmazonClientException(
	                    "Cannot load the credentials from the credential profiles file. " +
	                    "Please make sure that your credentials file is at the correct " +
	                    "location (~/.aws/credentials), and is in valid format.",
	                    e);
	        }

	        ec2 = new AmazonEC2Client(credentials);
	        sqs = new AmazonSQSClient(credentials);
	        
	        Region usEast1 = Region.getRegion(Regions.US_EAST_1);
	        
	        ec2.setRegion(usEast1);
			sqs.setRegion(usEast1);
			
			dynamoDB = new DynamoDBService(credentials);
			
	}
	
	public static void main(String[] args) throws Exception {
		init();
		
		final int poolSize = 4;
		long idle_time = 60; //idle time = 60 sec
	    		
		//Create thread pool
		ExecutorService threadPool = Executors.newFixedThreadPool(poolSize);
		
        //Get queue url
        GetQueueUrlResult urlResult = sqs.getQueueUrl("JobQueue");
        String jobQueueUrl = urlResult.getQueueUrl();
              
		// Receive messages
        System.out.println("Receiving messages from JobQueue.\n");
      
        //...Check idle state
        boolean terminate = false;
        boolean startClock = true;
        long start_time = 0,end_time;

        while(!terminate){       	
	        while(getQueueSize(sqs, jobQueueUrl) > 0){	        
	        	
	        	ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(jobQueueUrl);
		        List<Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();
		        
		        for (Message message : messages) {
		            System.out.println("  Message");
		            System.out.println("    MessageId:     " + message.getMessageId());
		            System.out.println("    ReceiptHandle: " + message.getReceiptHandle());
		            System.out.println("    MD5OfBody:     " + message.getMD5OfBody());
		            System.out.println("    Body:          " + message.getBody());
		          
		            //Get task
		            String task = message.getBody();
		            try{
			            dynamoDB.addTask(task,task);
			            String sleepLength = task.replaceAll("[^0-9]", "");
			            //System.out.println(Long.parseLong(sleepLength));
			            
			            //Execute task
			            threadPool.submit(new WorkerThread(Long.parseLong(sleepLength)));
			            	            
			            // Delete the message
			            String messageRecieptHandle = message.getReceiptHandle();
			            sqs.deleteMessage(new DeleteMessageRequest(jobQueueUrl, messageRecieptHandle));
		            }catch(ConditionalCheckFailedException ccf){
		        	   //DO something...
		            }
		            
		        }
		        
		        startClock = true;
	        }
	        
	        //Start clock to measure idle time
	        if(startClock){
	        	startClock = false;
	        	start_time = System.currentTimeMillis();
	        }else{
	        	end_time = System.currentTimeMillis();
	        	long elapsed_time = (end_time - start_time) / 1000;
	        	if(elapsed_time > idle_time){        		
	        		terminate = true;
	        	}
	        }
        }
        
        System.out.println();
        
        threadPool.shutdown();
        // Wait until all threads are finished
        while(!threadPool.isTerminated()){
        	
        }
        
        //Terminate running instance
        cleanUpInstance();
        
	}
	
	private static void cleanUpInstance() {
		/*try {
			CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(spotInstanceRequestIds);
            ec2.cancelSpotInstanceRequests(cancelRequest);
		} catch (AmazonServiceException e) {
            // Write out any exceptions that may have occurred.
            System.out.println("Error cancelling instances");
            System.out.println("Caught Exception: " + e.getMessage());
            System.out.println("Reponse Status Code: " + e.getStatusCode());
            System.out.println("Error Code: " + e.getErrorCode());
            System.out.println("Request ID: " + e.getRequestId());
        }*/
		
	  	List<String> instanceId = new ArrayList<String>();
	    String inputLine;
	    URL EC2MetaData;

	    try {
			EC2MetaData = new URL("http://169.254.169.254/latest/meta-data/instance-id");	
		    URLConnection EC2MD = EC2MetaData.openConnection();
		    BufferedReader in = new BufferedReader(new InputStreamReader(EC2MD.getInputStream()));
		    while ((inputLine = in.readLine()) != null) {
		    	instanceId.add(inputLine);
		    }
		    in.close();
		} catch (MalformedURLException e1) {
			e1.printStackTrace();
		} catch (IOException e) {
	    	e.printStackTrace();
		}	    

	    try {
            // Terminate instances.
            TerminateInstancesRequest terminateRequest = new TerminateInstancesRequest(instanceId);
            ec2.terminateInstances(terminateRequest);
        } catch (AmazonServiceException e) {
            // Write out any exceptions that may have occurred.
           System.out.println("Error terminating instances");
           System.out.println("Caught Exception: " + e.getMessage());
           System.out.println("Reponse Status Code: " + e.getStatusCode());
           System.out.println("Error Code: " + e.getErrorCode());
           System.out.println("Request ID: " + e.getRequestId());
        }
	
	}

	public static int getQueueSize(AmazonSQS sqs, String queueUrl){
		HashMap<String, String> attributes;
		
		Collection<String> attributeNames = new ArrayList<String>();
		attributeNames.add("ApproximateNumberOfMessages");
		
		GetQueueAttributesRequest getAttributesRequest = new GetQueueAttributesRequest(queueUrl)
		.withAttributeNames(attributeNames);
		attributes = (HashMap<String, String>) sqs.getQueueAttributes(getAttributesRequest).getAttributes();
		
		return Integer.valueOf(attributes.get("ApproximateNumberOfMessages"));
	}
}