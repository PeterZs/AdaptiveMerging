package comp559.a2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;

import mintools.viewer.EasyViewer;
import no.uib.cipr.matrix.DenseVector;

import javax.vecmath.Point2d;
import javax.vecmath.Vector2d;
import javax.vecmath.Vector3d;

/**
 * Simple 2D rigid body based on image samples
 * @author kry
 */
public class RigidBody {
	
	/*
	 * Pointer to the parent of this body if it is merged
	 */
	RigidCollection parent = null;
    /** Unique identifier for this body */
    public int index;
    
    /** Variable to keep track of identifiers that can be given to rigid bodies */
    static public int nextIndex = 0;
    
    public boolean woken_up = false;
    
    /** visitID of this contact at this timestep. */
    boolean visited = false;
    
    /** Block approximation of geometry */
    ArrayList<Block> blocks;
    
    /** Boundary blocks */
    ArrayList<Block> boundaryBlocks;
        
    BVNode root;
    
    /** accumulator for forces acting on this body */
    Vector2d force = new Vector2d();
    
    /** accumulator for torques acting on this body */
    double torque;
    
    double massAngular;
    
    double massLinear;
        
    public boolean pinned;
    
    /**
     * Transforms points in Body coordinates to World coordinates
     */
    RigidTransform transformB2W = new RigidTransform();
    

    /**
     * Transforms points in World coordinates to Body coordinates
     */
    RigidTransform transformW2B = new RigidTransform();

    /*
     * Transforms points in body coordinates to collection coordinates, if a collection exists
     */
    RigidTransform transformB2C = new RigidTransform();
    
    /*
     * Transforms points in collection coordinates to body coordinates, if a collection exists
     */
    RigidTransform transformC2B = new RigidTransform();
    
    
    /** linear velocity */
    public Vector2d v = new Vector2d();
    
    /** Position of center of mass in the world frame */
    public Point2d x = new Point2d();
    
    /** initial position of center of mass in the world frame */
    Point2d x0 = new Point2d();
    
    /** orientation angle in radians */
    public double theta;
    
    /** angular velocity in radians per second */
    public double omega;

    /** inverse of the linear mass, or zero if pinned */
    double minv;
    
    /** inverse of the angular mass, or zero if pinned */
    double jinv;
   
    /** linear momentum of this body  **/
    public Vector2d p_lin = new Vector2d();
    
    /** angular momentum of this body  **/
    public double p_ang;
    
    /**is this body active(0), restrained(1) or sleeping (2)  **/
    public int active;
  
    /**rho vvalue, 0 if active, 1 if inactive, function of Kinetic energy when in transition**/
    public double rho;
    


    /** list of contacting bodies present with this rigidbody. cleared after every timestep, unless the contact was between two sleeping bodies**/
    public ArrayList<Contact> contact_list = new ArrayList<Contact>();

    
    /** list of contacting bodies present with this rigidbody. cleared after every timestep, unless the contact was between two sleeping bodies**/
    public ArrayList<BodyContact> body_contact_list = new ArrayList<BodyContact>();
    
    
    /** list of springs attached to the body**/
    public ArrayList<Spring> springs = new ArrayList<Spring>();
    
    /** determines whether or not a body is bound by pendulum constraint*/
    public boolean pendulum_body;
    
    /** keeps track of the activity of the last N steps, if it is false, means that should be asleep, if true, should be awake */  
    public ArrayList<Boolean> active_past = new ArrayList<Boolean>();
    
    /** a list of the total contact forces acting on this body in the last N steps  before sleeping or merging   */
    public ArrayList<Vector2d> contactForces = new ArrayList<Vector2d>();
    
    /** a list of the total contact torques acting on this body in the last N steps  before sleeping or merging   */
    public ArrayList<Double> contactTorques = new ArrayList<Double>();
    
    
    /** the total contact forces acting on it at this current time step, in world coordinates**/
    public Vector2d contactForce = new Vector2d();
    
    public boolean merged = false;
    
    DenseVector delta_V = new DenseVector(3);
    
    /**
     * Creates a new rigid body from a collection of blocks
     * @param blocks
     * @param boundaryBlocks
     */
    public RigidBody( ArrayList<Block> blocks, ArrayList<Block> boundaryBlocks ) {

        this.blocks = blocks;
        this.boundaryBlocks = boundaryBlocks;        
        // compute the mass and center of mass position        
        for ( Block b : blocks ) {
            double mass = b.getColourMass();
            massLinear += mass;            
            x0.x += b.j * mass;
            x0.y += b.i * mass; 
        }
        x0.scale ( 1 / massLinear );
        // set block positions in world and body coordinates 
        for ( Block b : blocks ) {
            b.pB.x = b.j - x0.x;
            b.pB.y = b.i - x0.y;
        }
        // compute the rotational inertia
        final Point2d zero = new Point2d(0,0);
        for ( Block b : blocks ) {
            double mass = b.getColourMass();
            massAngular += mass*b.pB.distanceSquared(zero);
        }
        // prevent zero angular inertia in the case of a single block
        if ( blocks.size() == 1 ) {
            Block b = blocks.get(0);
            double mass = b.getColourMass();
            massAngular = mass * (1+1)/12;
        }
        x.set(x0);        
        transformB2W.set( theta, x );
        transformW2B.set( theta, x );
        transformW2B.invert();
                
        root = new BVNode( boundaryBlocks, this );
        
        pinned = isAllBlueBlocks();
        
        if ( pinned ) {
            minv = 0;
            jinv = 0;
        } else {
            minv = 1/massLinear;
            jinv = 1/massAngular;
        }
        pendulum_body = false;
        // set our index
        index = nextIndex++;
        delta_V.zero();
    }
    
    /**
     * Creates a copy of the provided rigid body 
     * @param body
     */
    public RigidBody( RigidBody body ) {
        blocks = new ArrayList<Block>(body.blocks);
        boundaryBlocks = new ArrayList<Block>(body.boundaryBlocks);
        massLinear = body.massLinear;
        massAngular = body.massAngular;
        x0.set( body.x0 );
        x.set( body.x );
        v.set(body.v);
        theta = body.theta;
        omega = body.omega;
        // we can share the blocks and boundary blocks...
        // no need to update them as they are in the correct body coordinates already        
        updateTransformations();
        // We do need our own bounding volumes!  can't share!
        root = new BVNode( boundaryBlocks, body);        
        pinned = body.pinned;
        minv = body.minv;
        jinv = body.jinv;
        active=0;
        rho = 0;
        // set our index
        pendulum_body = false;
        index = nextIndex++;
        delta_V.zero();
    
    }
    
    /**
     * Updates the B2W and W2B transformations
     */
    public void updateTransformations() {
        transformB2W.set( theta, x );
        transformW2B.set( theta, x );
        transformW2B.invert();
    }
    
    
    /**
     * Apply a contact force specified in world coordinates
     * @param contactPointW
     * @param contactForceW
     */
    public void applyContactForceW( Point2d contactPointW, Vector2d contactForceW ) {
        force.add( contactForceW );
        // TODO: Objective 1: Compute the torque applied to the body
        
   
        // holds r vector.
        Vector2d r = new Vector2d(contactPointW);
        Point2d tempX = new Point2d(x);
        if (parent != null) {
        	parent.transformB2W.transform(tempX);
        }
        r.sub(tempX);
        Vector2d ortho_r = new Vector2d(-r.y, r.x);
 
        torque += ortho_r.dot(contactForceW);
        
  
    }
    
    /**
     * Advances the body state using symplectic Euler, first integrating accumulated force and torque 
     * (which are then set to zero), and then updating position and angle.  The internal rigid transforms
     * are also updated. 
     * @param dt step size
     */
    public void advanceTime( double dt ) {
        //update particles activity or sleepiness.
        double epsilon_1 = CollisionProcessor.sleepingThreshold.getValue();
    	double epsilon_2 =  CollisionProcessor.wakingThreshold.getValue();
       // this.set_activity(epsilon_1, epsilon_2);
    	
    	
        if ( !pinned ) {          

            
            double delta_vx =force.x * dt/massLinear;
            double delta_vy =force.x * dt/massLinear;
            double delta_omega =force.x * dt/massLinear;
            
        	// non ARPS
        	v.x += force.x * dt/massLinear;
        	v.y += force.y * dt/massLinear;
        	omega += torque * dt/ massAngular;
        	double k = this.getKineticEnergy();
         
            if (CollisionProcessor.useAdaptiveHamiltonian.getValue()) {
            	this.set_activity_hamiltonian(epsilon_1, epsilon_2);
           	 	p_lin.x = v.x * massLinear;
           	 	p_lin.y = v.y * massLinear ;
           	 	p_ang = omega * massAngular ;
	            if(this.active==0) {
	
	            	//fully active, regular stepping
	            	x.x += p_lin.x /massLinear* dt;
	            	x.y += p_lin.y/massLinear * dt;
	            	theta += p_ang/massAngular *dt;

	            }else if (this.active==2) {
	            //	v.set(0, 0);
	            //	this.omega = 0;
	            	
	            	x.x += 0;
	            	x.y += 0;
	            	theta += 0;
	            }
	            else {
	            	//restrained motion
	            	double rho = ( k - epsilon_2)/(epsilon_1 - epsilon_2);
	            	Vector2d drhodp_lin = new Vector2d();
	            	drhodp_lin.scale(minv/(epsilon_1 - epsilon_2), p_lin);
	            	double drhodp_ang = p_ang * jinv/(epsilon_1 - epsilon_2);
	            	x.x += dt * ((1 - rho)*p_lin.x*minv - k*drhodp_lin.x);
	            	x.y +=  dt * ((1 - rho)*p_lin.y*minv - k*drhodp_lin.y);
	            	theta += dt * ((1 - rho)*p_ang*jinv - k*drhodp_ang);
	            }
            }else if(CollisionProcessor.use_contact_graph.getValue() ) {
            	//fully active, regular stepping
            	this.set_activity_contact_graph(epsilon_1);
            	if (active == 0 ) {
	            	x.x += v.x * dt;
	            	x.y += v.y * dt;
	            	theta += omega*dt;
            	}else{
            		v.set(0,0);
            		omega = 0;
            	}

            }
            else {
               	//fully active, regular stepping
             	this.set_activity_regular(epsilon_1);
            	x.x += v.x * dt;
            	x.y += v.y * dt;
            	theta += omega*dt;
            }
            
            updateTransformations();
            
        }   
        
        force.set(0,0);
        torque = 0;
    }
    
    private void set_activity_regular(double epsilon_1) {
		double k = getKineticEnergy();
		if (k < epsilon_1) {
			active = 2;
		}
		else {
			active = 0;
		}
		
	}

	/**
     * Computes the total kinetic energy of the body.
     * @return the total kinetic energy
     */
    public double getKineticEnergy() {
        return 0.5 * massLinear * v.lengthSquared() + 0.5 * massAngular * omega*omega; 
    }
    
    /** 
     * Computes the velocity of the provided point provided in world coordinates due
     * to motion of this body.   
     * @param contactPointW
     * @param result the velocity
     */
    public void getSpatialVelocity( Point2d contactPointW, Vector2d result ) {
        result.sub( contactPointW, x );
        result.scale( omega );        
        double xpart = -result.y;
        double ypart =  result.x;
        result.set( xpart, ypart );
        result.add( v );
    }
    
    /**
     * Checks if all blocks are shades of blue
     * @return true if all blue
     */
    boolean isAllBlueBlocks() {
        for ( Block b : blocks ) {
            if ( ! (b.c.x == b.c.y && b.c.x < b.c.z) ) return false;
        }
        return true;
    }

    /**
     * Checks to see if the point intersects the body in its current position
     * @param pW
     * @return true if intersection
     */
    public boolean intersect( Point2d pW ) {
    	
    		if (root.boundingDisc.isInDisc( pW ) ) {
            	Point2d pB = new Point2d();
            	transformW2B.transform( pW, pB );
            
            	for ( Block b : blocks ) {
                	if ( b.pB.distanceSquared( pB ) < Block.radius * Block.radius ) return true;
            	}
        	}
    	
        return false;
    	
    }
    
/* Input is a threshold value that determines if the particle is active or not. Will need to input a second threshold value when introducing transitional states.
 *
 * Checks what state the particle is in, changes the state of the particle if required, and returns rho:
 * rho =1 when the particle is inactive
 * rho = 0 when the particle is active
 * rho = f(k) a function of the kinetic energy if the particle is in a transition state (Not implemented yet)
 * 
 */
    public double set_activity_hamiltonian(double sleeping_threshold, double waking_threshold) {
    	double k = this.getKineticEnergy();

    	if (k < sleeping_threshold) {
    		//particle is inactive
    		this.active = 2;
    		rho = 1;
    	}else if (k < waking_threshold && k > sleeping_threshold){
    		//particle is in restrained motion
    		this.active = 1;
    		rho = (k -waking_threshold)/(sleeping_threshold - waking_threshold) ;
    	}else if (k > waking_threshold){
    		//particle is fully active
    		this.active=0;
    		rho = 0;
    	}
    	return rho;
    }
    
    public void set_activity_contact_graph(double sleeping_threshold) {
    	double k = this.getMetric();

    	if (k < sleeping_threshold) {
    		//particle is inactive
    		this.active_past.add(false);
    	}else {
    		//particle is active
    		this.active_past.add(true);
    	}
    	if(this.active_past.size() > CollisionProcessor.sleep_accum.getValue()) {
    		//if we have too much data, remove the oldest one.
    		this.active_past.remove(0);
    	}else if(active_past.size() < CollisionProcessor.sleep_accum.getValue()) {
    		//without enough data, particle is active as we wait for more
    		this.active = 0;
    		return;
    	}

    	
    	this.active = 2;
    	for (Boolean a : active_past) {
    		if (a == true) this.active = 0;
    	}
    	if (force.length() > CollisionProcessor.impulseTolerance.getValue()) {
    		this.active = 0;
    	} 
    
    }
    
    private double getMetric() {
    	return 0.5 *  v.lengthSquared() + 0.5 * omega*omega; 
	}

	/**
     * Resets this rigid body to its initial position and zero velocity, recomputes transforms
     */
    public void reset() {
        x.set(x0);        
        theta = 0;
        v.set(0,0);
        omega = 0;
        force.set(0, 0);
        torque = 0;
        p_lin.set(0, 0);
        p_ang = 0;
        transformB2W.set( theta, x );
        transformW2B.set( transformB2W );
        transformW2B.invert();
        
        transformB2C.T.setIdentity();
        transformC2B.T.setIdentity();
        contact_list.clear();
        body_contact_list.clear();
        active = 0;
        active_past.clear();
        parent=null;
        
    }
    
    /** Map to keep track of display list IDs for drawing our rigid bodies efficiently */
    static private HashMap<ArrayList<Block>,Integer> mapBlocksToDisplayList = new HashMap<ArrayList<Block>,Integer>();
    
    /** display list ID for this rigid body */
    int myListID = -1;

	public boolean created = false;

    
    /**
     * Deletes all display lists.
     * This is called when clearing all rigid bodies from the simulation, or when display lists need to be updated due to 
     * changing transparency of the blocks.
     * @param gl
     */
    static public void clearDisplayLists( GL2 gl ) {
        for ( int id : mapBlocksToDisplayList.values() ) {
            gl.glDeleteLists(id, 1);
        }
        mapBlocksToDisplayList.clear();
    }
    
    /** 
     * Draws the blocks of a rigid body
     * @param drawable
     */
    public void display( GLAutoDrawable drawable ) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();
        gl.glTranslated( x.x, x.y, 0 );
        gl.glRotated(theta*180/Math.PI, 0,0,1);
        if ( myListID == -1 ) {
            Integer ID = mapBlocksToDisplayList.get(blocks);
            if ( ID == null ) {
                myListID = gl.glGenLists(1);
                gl.glNewList( myListID, GL2.GL_COMPILE_AND_EXECUTE );
                for ( Block b : blocks ) {
                    b.display( drawable );
                }
                gl.glEndList();
                mapBlocksToDisplayList.put( blocks, myListID );
            } else {
                myListID = ID;
                gl.glCallList(myListID);
            }
        } else {
            gl.glCallList(myListID);
        }
        gl.glPopMatrix();
    }

    
    /**
     * Draws the center of mass position with a circle.  The circle will be 
     * drawn differently if the block is at rest (i.e., close to zero kinetic energy)
     * @param drawable
     */
    public void displayCOM( GLAutoDrawable drawable ) {
        GL2 gl = drawable.getGL().getGL2();
        if (!this.pinned) {
        if ( this.active==0 || this.woken_up) {
	            gl.glPointSize(8);
	            gl.glColor3f(0,0,0.7f);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	            gl.glPointSize(4);
	            gl.glColor3f(1,1,1);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	        } else if (this.active==1) {
	            gl.glPointSize(8);
	            gl.glColor3f(0,0,0.7f);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	            gl.glPointSize(4);
	            gl.glColor3f(1,0,0);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	        }
	        else if (this.active==2 && !this.woken_up) {
	            gl.glPointSize(8);
	            gl.glColor3f(0,0,0.7f);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	            gl.glPointSize(4);
	            gl.glColor3f(0,0,1);
	            gl.glBegin( GL.GL_POINTS );
	            gl.glVertex2d(x.x, x.y);
	            gl.glEnd();
	        }
        }
    }

	public void displayConnection(GLAutoDrawable drawable, RigidBody c) {
		  GL2 gl = drawable.getGL().getGL2();
	        // draw a line between the two bodies but only if they're both not pinned
	        if ( !this.pinned && ! c.pinned ) {
	            gl.glLineWidth(2);
	            gl.glColor4f(0,.3f,0, 0.5f);
	            gl.glBegin( GL.GL_LINES );
	            gl.glVertex2d(this.x.x, this.x.y);
	            gl.glVertex2d(c.x.x, c.x.y);
	            gl.glEnd();
	        }
	}

	public void drawSpeedCOM(GLAutoDrawable drawable) {
		// TODO Auto-generated method stub
		  GL2 gl = drawable.getGL().getGL2();
		  double k = this.v.length() + this.omega;
		
		  if (k > 255) {
			  k = 255;
		  }
		  k/=10;
		  gl.glPointSize(8);
          gl.glColor3f((float)k,0, 0);
          gl.glBegin( GL.GL_POINTS );
          gl.glVertex2d(x.x, x.y);
          gl.glEnd();
          gl.glPointSize(4);
          gl.glColor3f((float)k,0, 0);
          gl.glBegin( GL.GL_POINTS );
          gl.glVertex2d(x.x, x.y);
          gl.glEnd();
	}

	
  public void printIndex(GLAutoDrawable drawable,  int font)
    {
        GL2 gl = drawable.getGL().getGL2();
        gl.glColor3f(1, 0, 0);
        gl.glRasterPos2d( this.x.x, this.x.y );            
      
		EasyViewer.glut.glutBitmapString(font, Integer.toString(this.index));
       
              
    }
    
	/*public void scale(Double value) {
		// TODO Auto-generated method stub
		ArrayList<Block> new_blocks = new ArrayList<Block>;
		
	} */


    
}